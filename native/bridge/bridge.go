// Command portalbridge implements the libportaltunnel v1 C ABI on top of the
// upstream portal-tunnel Go SDK (sdk.Exposure). It is built with
// -buildmode=c-archive to produce libportaltunnel.a for iOS targets.
//
// The original mobile bridge (portal-tunnel/mobile) was removed from
// upstream; this is a clean-room reimplementation following the agent's
// wiring in cmd/portal-tunnel/agent/manager.go.
package main

/*
#include <stdlib.h>

typedef void (*PortalEventCallback)(const char* tunnelID, const char* eventType, const char* payloadJSON);

static inline void invoke_callback(PortalEventCallback cb, const char* tunnelID, const char* eventType, const char* payloadJSON) {
    if (cb != NULL) {
        cb(tunnelID, eventType, payloadJSON);
    }
}
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
	"unsafe"

	"github.com/gosuda/portal-tunnel/v2/portal/identity"
	"github.com/gosuda/portal-tunnel/v2/sdk"
	"github.com/gosuda/portal-tunnel/v2/types"
	"github.com/gosuda/portal-tunnel/v2/utils"
)

// ---- wire types (must match the Kotlin/Swift SDKs' snake_case keys) ---------

type portalConfig struct {
	Name            string            `json:"name"`
	IdentityJSON    string            `json:"identity_json"`
	IdentityPath    string            `json:"identity_path"`
	Relays          []string          `json:"relays"`
	Discovery       *bool             `json:"discovery"`
	MaxActiveRelays int               `json:"max_active_relays"`
	BanMITM         bool              `json:"ban_mitm"`
	ECH             bool              `json:"ech"`
	Overlay         bool              `json:"overlay"`
	UDP             bool              `json:"udp"`
	TCP             bool              `json:"tcp"`
	Description     string            `json:"description"`
	Tags            []string          `json:"tags"`
	Owner           string            `json:"owner"`
	Thumbnail       string            `json:"thumbnail"`
	Hide            bool              `json:"hide"`
	StaticDir       string            `json:"static_dir"`
	StaticIndex     string            `json:"static_index"`
	TargetAddr      string            `json:"target_addr"`
	UDPAddr         string            `json:"udp_addr"`
	HTTPRoutes      []portalHTTPRoute `json:"http_routes"`
	X402            *portalX402       `json:"x402"`
}

type portalHTTPRoute struct {
	Prefix      string   `json:"prefix"`
	Upstream    string   `json:"upstream"`
	StaticRoot  string   `json:"static_root"`
	StaticIndex string   `json:"static_index"`
	Methods     []string `json:"methods"`
	Amount      string   `json:"amount"`
}

type portalX402 struct {
	PayTo            string   `json:"pay_to"`
	Testnet          bool     `json:"testnet"`
	Network          string   `json:"network"`
	Asset            string   `json:"asset"`
	Endpoints        []string `json:"endpoints"`
	FacilitatorToken string   `json:"facilitator_token"`
}

type portalMetadata struct {
	Description string   `json:"description"`
	Tags        []string `json:"tags"`
	Owner       string   `json:"owner"`
	Thumbnail   string   `json:"thumbnail"`
	Hide        *bool    `json:"hide"`
}

type portalRelayStatus struct {
	RelayURL  string `json:"relay_url"`
	PublicURL string `json:"public_url,omitempty"`
	UDPAddr   string `json:"udp_addr,omitempty"`
	TCPAddr   string `json:"tcp_addr,omitempty"`
	Version   string `json:"version,omitempty"`
	State     string `json:"state"`
	Failure   string `json:"failure,omitempty"`
	Error     string `json:"error,omitempty"`
}

type portalStatus struct {
	TunnelID   string              `json:"tunnel_id"`
	Name       string              `json:"name"`
	Address    string              `json:"address"`
	Active     bool                `json:"active"`
	PublicURLs []string            `json:"public_urls"`
	Relays     []portalRelayStatus `json:"relays"`
}

// ---- tunnel registry ---------------------------------------------------------

type tunnel struct {
	id       string
	name     string
	identity types.Identity
	exposure *sdk.Exposure
	cancel   context.CancelFunc
	serveErr chan error
	done     chan struct{}
	stopped  sync.Once
}

var (
	tunnelsMu sync.Mutex
	tunnels   = map[string]*tunnel{}

	callbackMu sync.RWMutex
	callback   C.PortalEventCallback
)

func emitEvent(tunnelID, eventType string, payload any) {
	callbackMu.RLock()
	cb := callback
	callbackMu.RUnlock()
	if cb == nil {
		return
	}
	data, err := json.Marshal(payload)
	if err != nil {
		return
	}
	cID := C.CString(tunnelID)
	cType := C.CString(eventType)
	cPayload := C.CString(string(data))
	// The callback copies its arguments synchronously (Kotlin/Native
	// toKString); freeing after the call keeps the ABI leak-free.
	C.invoke_callback(cb, cID, cType, cPayload)
	C.free(unsafe.Pointer(cID))
	C.free(unsafe.Pointer(cType))
	C.free(unsafe.Pointer(cPayload))
}

func statusOf(t *tunnel) portalStatus {
	relays := t.exposure.Relays()
	out := portalStatus{
		TunnelID:   t.id,
		Name:       t.name,
		Address:    t.identity.Address,
		PublicURLs: []string{},
		Relays:     make([]portalRelayStatus, 0, len(relays)),
	}
	for _, r := range relays {
		if r.Deselected {
			continue
		}
		if r.Active() {
			out.Active = true
		}
		if r.PublicURL != "" {
			out.PublicURLs = append(out.PublicURLs, r.PublicURL)
		}
		rs := portalRelayStatus{
			RelayURL:  r.RelayURL,
			PublicURL: r.PublicURL,
			UDPAddr:   r.UDPAddr,
			TCPAddr:   r.TCPAddr,
			Version:   r.Version,
			State:     string(r.State),
			Failure:   string(r.Failure),
		}
		if r.Err != nil {
			rs.Error = r.Err.Error()
		}
		out.Relays = append(out.Relays, rs)
	}
	return out
}

func emitStatus(t *tunnel) {
	emitEvent(t.id, "STATUS_CHANGED", statusOf(t))
}

func markStopped(t *tunnel) {
	t.stopped.Do(func() {
		tunnelsMu.Lock()
		delete(tunnels, t.id)
		tunnelsMu.Unlock()
		emitEvent(t.id, "STOPPED", map[string]any{})
		close(t.done)
	})
}

// ---- exported ABI ------------------------------------------------------------

//export PortalSetEventCallback
func PortalSetEventCallback(cb C.PortalEventCallback) {
	callbackMu.Lock()
	callback = cb
	callbackMu.Unlock()
}

//export PortalFreeString
func PortalFreeString(str *C.char) {
	C.free(unsafe.Pointer(str))
}

//export PortalGenerateIdentity
func PortalGenerateIdentity(cName *C.char, outIdentityJSON **C.char, outError **C.char) C.int {
	name := ""
	if cName != nil {
		name = strings.TrimSpace(C.GoString(cName))
	}
	if name == "" {
		name = utils.RandomID("portal_")
	}
	id, err := identity.Generate(name)
	if err != nil {
		return fail(outError, err)
	}
	data, err := identity.Marshal(id)
	if err != nil {
		return fail(outError, err)
	}
	*outIdentityJSON = C.CString(string(data))
	return 0
}

//export PortalParseIdentity
func PortalParseIdentity(cIdentityJSON *C.char, outIdentityJSON **C.char, outError **C.char) C.int {
	if cIdentityJSON == nil {
		return fail(outError, errors.New("identity json is required"))
	}
	id, err := identity.Parse([]byte(C.GoString(cIdentityJSON)))
	if err != nil {
		return fail(outError, err)
	}
	data, err := identity.Marshal(id)
	if err != nil {
		return fail(outError, err)
	}
	*outIdentityJSON = C.CString(string(data))
	return 0
}

//export PortalStart
func PortalStart(cConfigJSON *C.char, outTunnelID **C.char, outError **C.char) C.int {
	if cConfigJSON == nil {
		return fail(outError, errors.New("config json is required"))
	}
	var cfg portalConfig
	if err := json.Unmarshal([]byte(C.GoString(cConfigJSON)), &cfg); err != nil {
		return fail(outError, fmt.Errorf("decode config json: %w", err))
	}
	if cfg.IdentityJSON != "" && cfg.IdentityPath != "" {
		return fail(outError, errors.New("identity_json and identity_path are mutually exclusive"))
	}

	listenerIdentity, err := identity.LoadOrCreate(cfg.Name, cfg.TargetAddr, cfg.IdentityPath, cfg.IdentityJSON)
	if err != nil {
		return fail(outError, fmt.Errorf("resolve identity: %w", err))
	}
	relayURLs, err := utils.NormalizeRelayURLs(cfg.Relays...)
	if err != nil {
		return fail(outError, err)
	}

	discovery := true
	if cfg.Discovery != nil {
		discovery = *cfg.Discovery
	}
	opts := []sdk.Option{
		sdk.WithMITMProtection(cfg.BanMITM),
		sdk.WithMetadata(types.LeaseMetadata{
			Description: strings.TrimSpace(cfg.Description),
			Owner:       strings.TrimSpace(cfg.Owner),
			Thumbnail:   strings.TrimSpace(cfg.Thumbnail),
			Tags:        normalizeTags(cfg.Tags),
			Hide:        cfg.Hide,
		}),
	}
	if cfg.UDP {
		opts = append(opts, sdk.WithUDP())
	}
	if cfg.TCP {
		opts = append(opts, sdk.WithTCP())
	}
	if cfg.ECH {
		opts = append(opts, sdk.WithECH())
	}
	if cfg.Overlay {
		opts = append(opts, sdk.WithOverlay())
	}
	if discovery {
		opts = append(opts, sdk.WithDiscovery(cfg.MaxActiveRelays))
	}

	ctx, cancel := context.WithCancel(context.Background())
	exposure, err := sdk.Expose(ctx, listenerIdentity, relayURLs, opts...)
	if err != nil {
		cancel()
		return fail(outError, err)
	}

	t := &tunnel{
		id:       utils.RandomID("tunnel_"),
		name:     listenerIdentity.Name,
		identity: listenerIdentity,
		exposure: exposure,
		cancel:   cancel,
		serveErr: make(chan error, 1),
		done:     make(chan struct{}),
	}

	// Serving mirrors cmd/portal-tunnel/agent: explicit http_routes win,
	// then static_dir, then the raw TCP/UDP proxy to target_addr.
	handler, serveErr := buildHandler(cfg)
	if serveErr != nil {
		cancel()
		_ = exposure.Close()
		return fail(outError, serveErr)
	}
	go func() {
		var err error
		if handler != nil {
			err = sdk.RunHTTP(ctx, exposure, handler, "")
		} else {
			udpTarget := ""
			if cfg.UDP {
				udpTarget = cfg.UDPAddr
				if strings.TrimSpace(udpTarget) == "" {
					udpTarget = cfg.TargetAddr
				}
			}
			err = sdk.ProxyWithConfig(ctx, exposure, sdk.ProxyConfig{
				TCPTarget: cfg.TargetAddr,
				UDPTarget: udpTarget,
			})
		}
		if errors.Is(err, context.Canceled) || errors.Is(err, net.ErrClosed) {
			err = nil
		}
		t.serveErr <- err
	}()

	// Relay status fan-out → STATUS_CHANGED / MITM_SUSPECTED events.
	go func() {
		for update := range exposure.Updates() {
			if update.Failure == sdk.RelayFailureMITM {
				emitEvent(t.id, "MITM_SUSPECTED", map[string]any{"relay_url": update.RelayURL})
			}
			emitStatus(t)
		}
	}()

	// Serve-loop exit → ERROR (when it failed) then STOPPED. This goroutine
	// is the sole serveErr consumer; PortalStop waits on t.done instead so
	// the two never race for the buffered result.
	go func() {
		err := <-t.serveErr
		if err != nil {
			emitEvent(t.id, "ERROR", map[string]any{"error": err.Error()})
		}
		markStopped(t)
	}()

	tunnelsMu.Lock()
	tunnels[t.id] = t
	tunnelsMu.Unlock()

	emitEvent(t.id, "STARTED", map[string]any{"name": t.name})
	emitStatus(t)

	*outTunnelID = C.CString(t.id)
	return 0
}

func buildHandler(cfg portalConfig) (http.Handler, error) {
	routes := make([]sdk.HTTPRouteConfig, 0, len(cfg.HTTPRoutes)+1)
	for _, route := range cfg.HTTPRoutes {
		routes = append(routes, sdk.HTTPRouteConfig{
			Prefix:      route.Prefix,
			Upstream:    route.Upstream,
			StaticRoot:  route.StaticRoot,
			StaticIndex: route.StaticIndex,
			Methods:     route.Methods,
			Amount:      route.Amount,
		})
	}
	if cfg.StaticDir != "" {
		routes = append(routes, sdk.HTTPRouteConfig{
			Prefix:      "/",
			StaticRoot:  cfg.StaticDir,
			StaticIndex: cfg.StaticIndex,
		})
	}
	if len(routes) == 0 {
		return nil, nil
	}
	payment := types.X402Payment{}
	if cfg.X402 != nil {
		payment = types.X402Payment{
			Testnet:          cfg.X402.Testnet,
			Network:          cfg.X402.Network,
			Asset:            cfg.X402.Asset,
			PayTo:            cfg.X402.PayTo,
			Endpoints:        append([]string(nil), cfg.X402.Endpoints...),
			FacilitatorToken: cfg.X402.FacilitatorToken,
		}
	}
	return sdk.NewHTTPRoutes(routes, payment)
}

//export PortalStop
func PortalStop(cTunnelID *C.char, outError **C.char) C.int {
	t := lookup(cTunnelID)
	if t == nil {
		return fail(outError, errors.New("unknown tunnel"))
	}
	t.cancel()
	// Wait for the serve loop to unwind so STOPPED is emitted before the
	// caller observes success.
	select {
	case <-t.done:
	case <-time.After(15 * time.Second):
		return fail(outError, errors.New("tunnel stop timed out"))
	}
	return 0
}

//export PortalStopAll
func PortalStopAll() {
	tunnelsMu.Lock()
	all := make([]*tunnel, 0, len(tunnels))
	for _, t := range tunnels {
		all = append(all, t)
	}
	tunnelsMu.Unlock()
	for _, t := range all {
		t.cancel()
	}
	for _, t := range all {
		<-t.done
	}
}

//export PortalGetStatus
func PortalGetStatus(cTunnelID *C.char, outStatusJSON **C.char, outError **C.char) C.int {
	t := lookup(cTunnelID)
	if t == nil {
		return fail(outError, errors.New("unknown tunnel"))
	}
	data, err := json.Marshal(statusOf(t))
	if err != nil {
		return fail(outError, err)
	}
	*outStatusJSON = C.CString(string(data))
	return 0
}

//export PortalAddRelay
func PortalAddRelay(cTunnelID *C.char, cRelayURL *C.char, outError **C.char) C.int {
	t := lookup(cTunnelID)
	if t == nil {
		return fail(outError, errors.New("unknown tunnel"))
	}
	if cRelayURL == nil {
		return fail(outError, errors.New("relay url is required"))
	}
	if err := t.exposure.AddRelay(C.GoString(cRelayURL)); err != nil {
		return fail(outError, err)
	}
	return 0
}

//export PortalRemoveRelay
func PortalRemoveRelay(cTunnelID *C.char, cRelayURL *C.char, outError **C.char) C.int {
	t := lookup(cTunnelID)
	if t == nil {
		return fail(outError, errors.New("unknown tunnel"))
	}
	if cRelayURL == nil {
		return fail(outError, errors.New("relay url is required"))
	}
	if err := t.exposure.RemoveRelay(C.GoString(cRelayURL)); err != nil {
		return fail(outError, err)
	}
	return 0
}

//export PortalUpdateMetadata
func PortalUpdateMetadata(cTunnelID *C.char, cMetadataJSON *C.char, outError **C.char) C.int {
	t := lookup(cTunnelID)
	if t == nil {
		return fail(outError, errors.New("unknown tunnel"))
	}
	if cMetadataJSON == nil {
		return fail(outError, errors.New("metadata json is required"))
	}
	var meta portalMetadata
	if err := json.Unmarshal([]byte(C.GoString(cMetadataJSON)), &meta); err != nil {
		return fail(outError, fmt.Errorf("decode metadata json: %w", err))
	}
	hide := false
	if meta.Hide != nil {
		hide = *meta.Hide
	}
	if err := t.exposure.UpdateMetadata(types.LeaseMetadata{
		Description: strings.TrimSpace(meta.Description),
		Owner:       strings.TrimSpace(meta.Owner),
		Thumbnail:   strings.TrimSpace(meta.Thumbnail),
		Tags:        normalizeTags(meta.Tags),
		Hide:        hide,
	}); err != nil {
		return fail(outError, err)
	}
	return 0
}

// ---- helpers -----------------------------------------------------------------

func lookup(cTunnelID *C.char) *tunnel {
	if cTunnelID == nil {
		return nil
	}
	tunnelsMu.Lock()
	defer tunnelsMu.Unlock()
	return tunnels[C.GoString(cTunnelID)]
}

func fail(outError **C.char, err error) C.int {
	if outError != nil {
		*outError = C.CString(err.Error())
	}
	return 1
}

func normalizeTags(tags []string) []string {
	if len(tags) == 0 {
		return nil
	}
	out := make([]string, 0, len(tags))
	for _, tag := range tags {
		if tag = strings.TrimSpace(tag); tag != "" {
			out = append(out, tag)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

func main() {}
