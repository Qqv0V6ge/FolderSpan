#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>

// The URLs retain the scopes for the life of the file-manager process. No paths or
// bookmark data leave this local bridge. FSReleaseDirectories balances each scope.
static NSMutableDictionary<NSString *, NSURL *> *accessedURLs;

static char *jsonResult(NSDictionary *value) {
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:0 error:nil];
    return strdup([[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding].UTF8String);
}

static NSDictionary *failure(void) {
    return @{ @"error": @"Unable to authorize this folder. Please select it again." };
}

static NSDictionary *retainDirectory(NSURL *url) {
    @synchronized (NSFileManager.class) {
        if (!accessedURLs) accessedURLs = [NSMutableDictionary new];
        NSURL *held = accessedURLs[url.path];
        BOOL started = held != nil || [url startAccessingSecurityScopedResource];
        if (!started) return failure();
        NSError *error = nil;
        NSData *bookmark = [url bookmarkDataWithOptions:NSURLBookmarkCreationWithSecurityScope
                       includingResourceValuesForKeys:nil relativeToURL:nil error:&error];
        if (!bookmark) {
            if (!held) [url stopAccessingSecurityScopedResource];
            return failure();
        }
        if (!held) accessedURLs[url.path] = url;
        return @{ @"path": url.path, @"bookmark": [bookmark base64EncodedStringWithOptions:0] };
    }
}

char *FSContainerPaths(void) {
    @autoreleasepool {
        return jsonResult(@{ @"home": NSHomeDirectory(), @"temporary": NSTemporaryDirectory() });
    }
}

char *FSSelectDirectory(void) {
    @autoreleasepool {
        __block NSDictionary *result;
        void (^select)(void) = ^{
            NSOpenPanel *panel = [NSOpenPanel openPanel];
            panel.canChooseDirectories = YES;
            panel.canChooseFiles = NO;
            panel.allowsMultipleSelection = NO;
            panel.canCreateDirectories = YES;
            if ([panel runModal] == NSModalResponseOK && panel.URL) {
                result = retainDirectory(panel.URL);
            } else {
                result = @{ @"cancelled": @YES };
            }
        };
        if (NSThread.isMainThread) select();
        else dispatch_sync(dispatch_get_main_queue(), select);
        return jsonResult(result);
    }
}

char *FSResolveDirectory(const char *encoded) {
    @autoreleasepool {
        NSData *data = [[NSData alloc] initWithBase64EncodedString:
            [NSString stringWithUTF8String:encoded] options:0];
        BOOL stale = NO;
        NSError *error = nil;
        NSURL *url = data ? [NSURL URLByResolvingBookmarkData:data
            options:NSURLBookmarkResolutionWithSecurityScope | NSURLBookmarkResolutionWithoutUI
            relativeToURL:nil bookmarkDataIsStale:&stale error:&error] : nil;
        return jsonResult(url ? retainDirectory(url) : failure());
    }
}

void FSReleaseDirectories(void) {
    @synchronized (NSFileManager.class) {
        for (NSURL *url in accessedURLs.allValues) [url stopAccessingSecurityScopedResource];
        [accessedURLs removeAllObjects];
    }
}

void FSFreeString(char *value) { free(value); }
