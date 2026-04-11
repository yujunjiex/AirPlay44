/*
 * Replacement for RPiPlay's lib/dnssd.c.
 *
 * RPiPlay's original dnssd.c dlopen()s libdns_sd (Avahi compat shim on Linux,
 * Bonjour on macOS/Windows) to register _airplay._tcp and _raop._tcp. On
 * Android we use NsdManager from Kotlin for that, so we only need to satisfy
 * the dnssd_t API that raop.c calls into — specifically name + hw_addr so
 * the RTSP layer can echo them back in pairing responses.
 */

#include "dnssd.h"
#include <stdlib.h>
#include <string.h>

struct dnssd_s {
    char* name;
    int   name_len;
    char  hw_addr[6];
    int   hw_addr_len;
};

dnssd_t* dnssd_init(const char* name, int name_len, const char* hw_addr, int hw_addr_len, int* error) {
    if (hw_addr_len != 6) { if (error) *error = DNSSD_ERROR_HWADDRLEN; return NULL; }
    dnssd_t* d = (dnssd_t*)calloc(1, sizeof(dnssd_t));
    if (!d) { if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    d->name = (char*)malloc(name_len + 1);
    if (!d->name) { free(d); if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    memcpy(d->name, name, name_len);
    d->name[name_len] = '\0';
    d->name_len = name_len;
    memcpy(d->hw_addr, hw_addr, 6);
    d->hw_addr_len = 6;
    if (error) *error = DNSSD_ERROR_NOERROR;
    return d;
}

int  dnssd_register_raop   (dnssd_t* d, unsigned short port) { (void)d; (void)port; return 0; }
int  dnssd_register_airplay(dnssd_t* d, unsigned short port) { (void)d; (void)port; return 0; }
void dnssd_unregister_raop   (dnssd_t* d) { (void)d; }
void dnssd_unregister_airplay(dnssd_t* d) { (void)d; }

const char* dnssd_get_airplay_txt(dnssd_t* d, int* length) { (void)d; if (length) *length = 0; return ""; }
const char* dnssd_get_name(dnssd_t* d, int* length)       { if (length) *length = d->name_len; return d->name; }
const char* dnssd_get_hw_addr(dnssd_t* d, int* length)    { if (length) *length = d->hw_addr_len; return d->hw_addr; }

void dnssd_destroy(dnssd_t* d) {
    if (!d) return;
    free(d->name);
    free(d);
}
