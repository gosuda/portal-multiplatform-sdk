/*
 * Test stub implementing the libportaltunnel v1 C ABI.
 *
 * Linked into the linuxX64 test binary so the Kotlin/Native cinterop adapter
 * is exercised end-to-end (symbol resolution, out-params, event callback,
 * PortalFreeString ownership) without the real Go engine, whose mobile
 * bridge sources are not yet recovered (see native/source-lock.json).
 *
 * Behavior contract of the stub:
 *  - PortalStart emits STARTED then STATUS_CHANGED synchronously, before
 *    returning the tunnel id. This exercises the SDK's orphan-event buffer.
 *  - PortalGetStatus reports an active tunnel with one ready relay.
 *  - All other mutations succeed with code 0.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef void (*PortalEventCallback)(const char* tunnelID, const char* eventType, const char* payloadJSON);

static PortalEventCallback g_callback = NULL;

void PortalSetEventCallback(PortalEventCallback cb) { g_callback = cb; }

void PortalFreeString(char* str) { free(str); }

static char* dupstr(const char* s) {
    size_t n = strlen(s) + 1;
    char* p = (char*)malloc(n);
    if (p) memcpy(p, s, n);
    return p;
}

static void set_err(char** outError, const char* msg) {
    if (outError) *outError = dupstr(msg);
}

static const char* kStatusJson =
    "{\"tunnel_id\":\"stub-tunnel-1\",\"name\":\"stub\",\"address\":\"0xstub\","
    "\"active\":true,\"public_urls\":[\"https://stub.portal.example\"],"
    "\"relays\":[{\"relay_url\":\"https://relay.stub\",\"state\":\"ready\","
    "\"public_url\":\"https://stub.portal.example\"}]}";

int PortalGenerateIdentity(const char* name, char** outIdentityJSON, char** outError) {
    (void)outError;
    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"name\":\"%s\",\"address\":\"0xstubidentity\"}",
             name ? name : "");
    *outIdentityJSON = dupstr(buf);
    return 0;
}

int PortalParseIdentity(const char* identityJSON, char** outIdentityJSON, char** outError) {
    if (!identityJSON || strstr(identityJSON, "\"address\"") == NULL) {
        set_err(outError, "invalid identity document");
        return 1;
    }
    *outIdentityJSON = dupstr(identityJSON);
    return 0;
}

int PortalStart(const char* configJSON, char** outTunnelID, char** outError) {
    (void)configJSON;
    (void)outError;
    const char* id = "stub-tunnel-1";
    if (g_callback) {
        g_callback(id, "STARTED", "{\"name\":\"stub\"}");
        g_callback(id, "STATUS_CHANGED", kStatusJson);
    }
    *outTunnelID = dupstr(id);
    return 0;
}

int PortalStop(const char* tunnelID, char** outError) {
    if (!tunnelID || strcmp(tunnelID, "stub-tunnel-1") != 0) {
        set_err(outError, "unknown tunnel");
        return 1;
    }
    if (g_callback) g_callback(tunnelID, "STOPPED", "{}");
    return 0;
}

void PortalStopAll(void) {}

int PortalGetStatus(const char* tunnelID, char** outStatusJSON, char** outError) {
    (void)tunnelID;
    (void)outError;
    *outStatusJSON = dupstr(kStatusJson);
    return 0;
}

int PortalAddRelay(const char* tunnelID, const char* relayURL, char** outError) {
    (void)tunnelID;
    (void)relayURL;
    (void)outError;
    return 0;
}

int PortalRemoveRelay(const char* tunnelID, const char* relayURL, char** outError) {
    (void)tunnelID;
    (void)relayURL;
    (void)outError;
    return 0;
}

int PortalUpdateMetadata(const char* tunnelID, const char* metadataJSON, char** outError) {
    (void)tunnelID;
    (void)metadataJSON;
    (void)outError;
    return 0;
}
