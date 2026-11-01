package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/gosuda/portal-tunnel/v2/portal/identity"
)

func TestDomainPrefixChangesLeaseNameWithoutChangingStoredIdentity(t *testing.T) {
	path := filepath.Join(t.TempDir(), "identity.json")
	stored, err := identity.LoadOrCreate("stored-name", path, "")
	if err != nil {
		t.Fatal(err)
	}
	before, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	prefix := "My-Arcade"
	for _, cfg := range []portalConfig{
		{IdentityPath: path, DomainPrefix: &prefix},
		{IdentityJSON: string(before), DomainPrefix: &prefix},
	} {
		resolved, err := listenerIdentityFor(cfg)
		if err != nil {
			t.Fatal(err)
		}
		if resolved.Name != "my-arcade" {
			t.Fatalf("lease name = %q, want my-arcade", resolved.Name)
		}
		if resolved.Address != stored.Address || resolved.PrivateKey != stored.PrivateKey || resolved.TokenSecret != stored.TokenSecret {
			t.Fatal("domain prefix changed signing identity or token secret")
		}
	}
	after, err := os.ReadFile(path)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatal("domain prefix rewrote persisted identity")
	}
	fallback, err := listenerIdentityFor(portalConfig{IdentityPath: path})
	if err != nil || fallback.Name != stored.Name || fallback.Address != stored.Address {
		t.Fatal("unset prefix did not retain the stored identity")
	}
}

func TestInvalidDomainPrefixDoesNotCreateIdentity(t *testing.T) {
	for _, prefix := range []string{"", " ", "-arcade", "arcade-", "two.labels", "two words", strings.Repeat("a", 64), strings.Repeat("a", 40), "arcade-" + strings.Repeat("0", 40)} {
		t.Run(prefix, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "identity.json")
			_, err := listenerIdentityFor(portalConfig{Name: "stored-name", IdentityPath: path, DomainPrefix: &prefix})
			if err == nil {
				t.Fatal("invalid domain prefix accepted")
			}
			if _, err := os.Stat(path); !os.IsNotExist(err) {
				t.Fatal("invalid prefix created identity before validation")
			}
		})
	}
}
