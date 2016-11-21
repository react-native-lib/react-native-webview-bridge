#import "RCTWKWebView.h"

#import <WebKit/WebKit.h>
#import <UIKit/UIKit.h>

#import "RCTAutoInsetsProtocol.h"
#import "RCTConvert.h"
#import "RCTEventDispatcher.h"
#import "RCTLog.h"
#import "RCTUtils.h"
#import "RCTView.h"
#import "UIView+React.h"

#define NSStringMultiline(...) [[NSString alloc] initWithCString:#__VA_ARGS__ encoding:NSUTF8StringEncoding]
NSString *const RCTWebViewBridgeSchema = @"wvb";

@interface RCTWKWebView () <WKNavigationDelegate, RCTAutoInsetsProtocol>

@property (nonatomic, copy) RCTDirectEventBlock onLoadingStart;
@property (nonatomic, copy) RCTDirectEventBlock onLoadingFinish;
@property (nonatomic, copy) RCTDirectEventBlock onLoadingError;
@property (nonatomic, copy) RCTDirectEventBlock onShouldStartLoadWithRequest;
@property (nonatomic, copy) RCTDirectEventBlock onProgress;
@property (nonatomic, copy) RCTDirectEventBlock onBridgeMessage;

@end

@implementation RCTWKWebView
{
  WKWebView *_webView;
  NSString *_injectedJavaScript;
}

- (instancetype)initWithFrame:(CGRect)frame
{
  if ((self = [super initWithFrame:frame])) {
    super.backgroundColor = [UIColor clearColor];
    _automaticallyAdjustContentInsets = YES;
    _contentInset = UIEdgeInsetsZero;
    _webView = [[WKWebView alloc] initWithFrame:self.bounds];
    _webView.navigationDelegate = self;
    [_webView addObserver:self forKeyPath:@"estimatedProgress" options:NSKeyValueObservingOptionNew context:nil];
    [self addSubview:_webView];
  }
  return self;
}

RCT_NOT_IMPLEMENTED(- (instancetype)initWithCoder:(NSCoder *)aDecoder)

- (void)loadRequest:(NSURLRequest *)request {
  if (request.URL && _sendCookies) {
    NSDictionary *cookies = [NSHTTPCookie requestHeaderFieldsWithCookies:[[NSHTTPCookieStorage sharedHTTPCookieStorage] cookiesForURL:request.URL]];
    if ([cookies objectForKey:@"Cookie"]) {
      NSMutableURLRequest *mutableRequest = request.mutableCopy;
      [mutableRequest addValue:cookies[@"Cookie"] forHTTPHeaderField:@"Cookie"];
      request = mutableRequest;
    }
  }

  [_webView loadRequest:request];
}

- (void)goForward
{
  [_webView goForward];
}

- (void)goBack
{
  [_webView goBack];
}

- (void)reload
{
  NSURLRequest *request = [RCTConvert NSURLRequest:self.source];
  if (request.URL && !_webView.URL.absoluteString.length) {
    [self loadRequest:request];
  }
  else {
    [_webView reload];
  }
}


- (void)sendToBridge:(NSString *)message
{
    //we are warpping the send message in a function to make sure that if
    //WebView is not injected, we don't crash the app.
    NSString *format = NSStringMultiline(
                                         (function(){
        if (WebViewBridge && WebViewBridge.__push__) {
            WebViewBridge.__push__('%@');
        }
    }());
                                         );
    
    NSString *command = [NSString stringWithFormat: format, message];
    [_webView evaluateJavaScript:command completionHandler:^(id result, NSError *error) {
        
    }];
}

- (void)setSource:(NSDictionary *)source
{
  if (![_source isEqualToDictionary:source]) {
    _source = [source copy];

    // Check for a static html source first
    NSString *html = [RCTConvert NSString:source[@"html"]];
    if (html) {
      NSURL *baseURL = [RCTConvert NSURL:source[@"baseUrl"]];
      if (!baseURL) {
        baseURL = [NSURL URLWithString:@"about:blank"];
      }
      [_webView loadHTMLString:html baseURL:baseURL];
      return;
    }

    NSURLRequest *request = [RCTConvert NSURLRequest:source];
    // Because of the way React works, as pages redirect, we actually end up
    // passing the redirect urls back here, so we ignore them if trying to load
    // the same url. We'll expose a call to 'reload' to allow a user to load
    // the existing page.
    if ([request.URL isEqual:_webView.URL]) {
      return;
    }
    if (!request.URL) {
      // Clear the webview
      [_webView loadHTMLString:@"" baseURL:nil];
      return;
    }
    [self loadRequest:request];
  }
}

- (void)layoutSubviews
{
  [super layoutSubviews];
  _webView.frame = self.bounds;
}

- (void)setContentInset:(UIEdgeInsets)contentInset
{
  _contentInset = contentInset;
  [RCTView autoAdjustInsetsForView:self
                    withScrollView:_webView.scrollView
                      updateOffset:NO];
}

- (void)setBackgroundColor:(UIColor *)backgroundColor
{
  CGFloat alpha = CGColorGetAlpha(backgroundColor.CGColor);
  self.opaque = _webView.opaque = (alpha == 1.0);
  _webView.backgroundColor = backgroundColor;
}

- (UIColor *)backgroundColor
{
  return _webView.backgroundColor;
}

- (NSMutableDictionary<NSString *, id> *)baseEvent
{
  NSMutableDictionary<NSString *, id> *event = [[NSMutableDictionary alloc] initWithDictionary:@{
    @"url": _webView.URL.absoluteString ?: @"",
    @"loading" : @(_webView.loading),
    @"title": _webView.title,
    @"canGoBack": @(_webView.canGoBack),
    @"canGoForward" : @(_webView.canGoForward),
  }];

  return event;
}

- (void)refreshContentInset
{
  [RCTView autoAdjustInsetsForView:self
                    withScrollView:_webView.scrollView
                      updateOffset:YES];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary *)change
                       context:(void *)context
{
  if ([keyPath isEqualToString:@"estimatedProgress"]) {
    if (!_onProgress) {
      return;
    }
    _onProgress(@{@"progress": [change objectForKey:NSKeyValueChangeNewKey]});
  }
}

- (void)dealloc
{
  @try {
    [_webView removeObserver:self forKeyPath:@"estimatedProgress"];
  }
  @catch (NSException * __unused exception) {}
}

#pragma mark - WKNavigationDelegate methods

- (void)webView:(__unused WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler
{
  NSURLRequest *request = navigationAction.request;
  NSURL* url = request.URL;
  NSString* scheme = url.scheme;

  BOOL isJSNavigation = [scheme isEqualToString:RCTJSNavigationScheme];

    if (!isJSNavigation && [request.URL.scheme isEqualToString:RCTWebViewBridgeSchema]) {
        
        [webView evaluateJavaScript:@"WebViewBridge.__fetch__()" completionHandler:^(id message, NSError *error) {
            NSMutableDictionary<NSString *, id> *onBridgeMessageEvent = [[NSMutableDictionary alloc] initWithDictionary:@{
                                                                                                                          @"messages": [self stringArrayJsonToArray: message]
                                                                                                                          }];
            _onBridgeMessage(onBridgeMessageEvent);
            if (_onLoadingStart) {
                // We have this check to filter out iframe requests and whatnot
                BOOL isTopFrame = [url isEqual:request.mainDocumentURL];
                if (isTopFrame) {
                    NSMutableDictionary<NSString *, id> *event = [self baseEvent];
                    [event addEntriesFromDictionary: @{
                                                       @"url": url.absoluteString,
                                                       @"navigationType": @(navigationAction.navigationType)
                                                       }];
                    _onLoadingStart(event);
                }
            }
            
            decisionHandler(WKNavigationActionPolicyCancel);
  
        }];
    }
    
  // skip this for the JS Navigation handler
  if (!isJSNavigation && _onShouldStartLoadWithRequest) {
    NSMutableDictionary<NSString *, id> *event = [self baseEvent];
    [event addEntriesFromDictionary: @{
      @"url": (request.URL).absoluteString,
      @"navigationType": @(navigationAction.navigationType)
    }];
    if (![self.delegate webView:self
      shouldStartLoadForRequest:event
                   withCallback:_onShouldStartLoadWithRequest]) {
      return decisionHandler(WKNavigationActionPolicyCancel);
    }
  }

  if (_onLoadingStart) {
    // We have this check to filter out iframe requests and whatnot
    BOOL isTopFrame = [url isEqual:request.mainDocumentURL];
    if (isTopFrame) {
      NSMutableDictionary<NSString *, id> *event = [self baseEvent];
      [event addEntriesFromDictionary: @{
        @"url": url.absoluteString,
        @"navigationType": @(navigationAction.navigationType)
      }];
      _onLoadingStart(event);
    }
  }

  if (isJSNavigation) {
    decisionHandler(WKNavigationActionPolicyCancel);
  }
  else if (navigationAction.targetFrame && ([scheme isEqualToString:@"http"] || [scheme isEqualToString:@"https"])) {
    decisionHandler(WKNavigationActionPolicyAllow);
  }
  else {
    if (![scheme isEqualToString:@"about"]) {
        [[UIApplication sharedApplication] openURL:url];
    }
    decisionHandler(WKNavigationActionPolicyAllow);
  }
}

- (void)webView:(__unused WKWebView *)webView didFailProvisionalNavigation:(__unused WKNavigation *)navigation withError:(NSError *)error
{
  if (_onLoadingError) {
    if ([error.domain isEqualToString:NSURLErrorDomain] && error.code == NSURLErrorCancelled) {
      // NSURLErrorCancelled is reported when a page has a redirect OR if you load
      // a new URL in the WebView before the previous one came back. We can just
      // ignore these since they aren't real errors.
      // http://stackoverflow.com/questions/1024748/how-do-i-fix-nsurlerrordomain-error-999-in-iphone-3-0-os
      return;
    }

    NSMutableDictionary<NSString *, id> *event = [self baseEvent];
    [event addEntriesFromDictionary:@{
      @"domain": error.domain,
      @"code": @(error.code),
      @"description": error.localizedDescription,
    }];
    _onLoadingError(event);
  }
}

- (void)webView:(WKWebView *)webView didFinishNavigation:(__unused WKNavigation *)navigation
{
    
    //injecting WebViewBridge Script
    NSString *webViewBridgeScriptContent = [self webViewBridgeScript];
    [webView evaluateJavaScript:webViewBridgeScriptContent completionHandler:^(id result, NSError *error) {
        if (_injectedJavaScript != nil) {
            [webView evaluateJavaScript:_injectedJavaScript completionHandler:^(id result, NSError *error) {
                NSMutableDictionary<NSString *, id> *event = [self baseEvent];
                event[@"jsEvaluationValue"] = [NSString stringWithFormat:@"%@", result];
                _onLoadingFinish(event);
            }];
        }
        // we only need the final 'finishLoad' call so only fire the event when we're actually done loading.
        else if (_onLoadingFinish && !webView.loading && ![webView.URL.absoluteString isEqualToString:@"about:blank"]) {
            _onLoadingFinish([self baseEvent]);
        }
    }];
}



- (NSArray*)stringArrayJsonToArray:(NSString *)message
{
    return [NSJSONSerialization JSONObjectWithData:[message dataUsingEncoding:NSUTF8StringEncoding]
                                           options:NSJSONReadingAllowFragments
                                             error:nil];
}

//since there is no easy way to load the static lib resource in ios,
//we are loading the script from this method.
- (NSString *)webViewBridgeScript {
    // NSBundle *bundle = [NSBundle mainBundle];
    // NSString *webViewBridgeScriptFile = [bundle pathForResource:@"webviewbridge"
    //                                                      ofType:@"js"];
    // NSString *webViewBridgeScriptContent = [NSString stringWithContentsOfFile:webViewBridgeScriptFile
    //                                                                  encoding:NSUTF8StringEncoding
    //                                                                     error:nil];
    
    return NSStringMultiline(
                             (function (window) {
        'use strict';
        
        //Make sure that if WebViewBridge already in scope we don't override it.
        if (window.WebViewBridge) {
            return;
        }
        
        var RNWBSchema = 'wvb';
        var sendQueue = [];
        var receiveQueue = [];
        var doc = window.document;
        var customEvent = doc.createEvent('Event');
        
        function callFunc(func, message) {
            if ('function' === typeof func) {
                func(message);
            }
        }
        
        function signalNative() {
            window.location = RNWBSchema + '://message' + new Date().getTime();
        }
        
        //I made the private function ugly signiture so user doesn't called them accidently.
        //if you do, then I have nothing to say. :(
        var WebViewBridge = {
            //this function will be called by native side to push a new message
            //to webview.
        __push__: function (message) {
            receiveQueue.push(message);
            //reason I need this setTmeout is to return this function as fast as
            //possible to release the native side thread.
            setTimeout(function () {
                var message = receiveQueue.pop();
                callFunc(WebViewBridge.onMessage, message);
            }, 15); //this magic number is just a random small value. I don't like 0.
        },
        __fetch__: function () {
            //since our sendQueue array only contains string, and our connection to native
            //can only accept string, we need to convert array of strings into single string.
            var messages = JSON.stringify(sendQueue);
            
            //we make sure that sendQueue is resets
            sendQueue = [];
            
            //return the messages back to native side.
            return messages;
        },
            //make sure message is string. because only string can be sent to native,
            //if you don't pass it as string, onError function will be called.
        send: function (message) {
            if ('string' !== typeof message) {
                callFunc(WebViewBridge.onError, "message is type '" + typeof message + "', and it needs to be string");
                return;
            }
            
            //we queue the messages to make sure that native can collects all of them in one shot.
            sendQueue.push(message);
            //signal the objective-c that there is a message in the queue
            signalNative();
        },
        onMessage: null,
        onError: null
        };
        
        window.WebViewBridge = WebViewBridge;
        
        //dispatch event
        customEvent.initEvent('WebViewBridge', true, true);
        doc.dispatchEvent(customEvent);
    }(window));
                             );
}


@end
