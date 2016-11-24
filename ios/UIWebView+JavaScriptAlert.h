//
//  UIWebView+JavaScriptAlert.h
//  React-Native-Webview-Bridge
//
//  Created by joshua on 16/11/24.
//  Copyright © 2016年 alinz. All rights reserved.
//

#ifndef UIWebView_JavaScriptAlert_h
#define UIWebView_JavaScriptAlert_h
#import <UIKit/UIKit.h>

@interface UIWebView (JavaScriptAlert)
- (void)webView:(UIWebView *)sender runJavaScriptAlertPanelWithMessage:(NSString *)message initiatedByFrame:(id)frame;
- (BOOL)webView:(UIWebView *)sender runJavaScriptConfirmPanelWithMessage:(NSString *)message initiatedByFrame:(id)frame;
- (NSString *) webView:(UIWebView *)view runJavaScriptTextInputPanelWithPrompt:(NSString *)prompt defaultText:(NSString *)text initiatedByFrame:(id)frame;
@end

#endif /* UIWebView_JavaScriptAlert_h */
