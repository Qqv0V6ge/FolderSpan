#import <Foundation/Foundation.h>
#import <Security/Security.h>

static inline NSURLCredential *fm_credential_for_trust(SecTrustRef trust) {
    return [NSURLCredential credentialForTrust:trust];
}
