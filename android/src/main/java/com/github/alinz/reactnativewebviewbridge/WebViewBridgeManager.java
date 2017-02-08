/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.github.alinz.reactnativewebviewbridge;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Picture;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup.LayoutParams;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.common.ReactConstants;
import com.facebook.react.common.build.ReactBuildConfig;
import com.facebook.react.module.annotations.ReactModule;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.UIManagerModule;
import com.facebook.react.uimanager.annotations.ReactProp;
import com.facebook.react.uimanager.events.ContentSizeChangeEvent;
import com.facebook.react.uimanager.events.Event;
import com.facebook.react.uimanager.events.EventDispatcher;
import com.facebook.react.views.webview.WebViewConfig;
import com.facebook.react.views.webview.events.TopLoadingErrorEvent;
import com.facebook.react.views.webview.events.TopLoadingFinishEvent;
import com.facebook.react.views.webview.events.TopLoadingStartEvent;
import com.facebook.react.views.webview.events.TopMessageEvent;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Manages instances of {@link WebView}
 *
 * Can accept following commands:
 *  - GO_BACK
 *  - GO_FORWARD
 *  - RELOAD
 *
 * {@link WebView} instances could emit following direct events:
 *  - topLoadingFinish
 *  - topLoadingStart
 *  - topLoadingError
 *
 * Each event will carry the following properties:
 *  - target - view's react tag
 *  - url - url set for the webview
 *  - loading - whether webview is in a loading state
 *  - title - title of the current page
 *  - canGoBack - boolean, whether there is anything on a history stack to go back
 *  - canGoForward - boolean, whether it is possible to request GO_FORWARD command
 */
@ReactModule(name = WebViewBridgeManager.REACT_CLASS)
public class WebViewBridgeManager extends SimpleViewManager<WebView> {

    protected static final String REACT_CLASS = "RCTWebViewBridge";

    private static final String HTML_ENCODING = "UTF-8";
    private static final String HTML_MIME_TYPE = "text/html; charset=utf-8";
    private static final String BRIDGE_NAME = "WebViewBridge";

    private static final String HTTP_METHOD_POST = "POST";

    public static final int COMMAND_GO_BACK = 1;
    public static final int COMMAND_GO_FORWARD = 2;
    public static final int COMMAND_RELOAD = 3;
    public static final int COMMAND_STOP_LOADING = 4;
    public static final int COMMAND_POST_MESSAGE = 5;

    // Use `webView.loadUrl("about:blank")` to reliably reset the view
    // state and release page resources (including any running JavaScript).
    private static final String BLANK_URL = "about:blank";

    private WebViewConfig mWebViewConfig;
    private @Nullable WebView.PictureListener mPictureListener;

    private ValueCallback<Uri> mUploadMessage;
    private ValueCallback<Uri[]> mUploadMessages;

    private ReactApplicationContext mReactApplicationContext;

    private final static int FILECHOOSER_RESULTCODE = 1001;

    private static class ReactWebViewClient extends WebViewClient {

        private boolean mLastLoadFailed = false;

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.proceed();
        }

        @Override
        public void onPageFinished(WebView webView, String url) {
            super.onPageFinished(webView, url);

            if (!mLastLoadFailed) {
                ReactWebView reactWebView = (ReactWebView) webView;
                reactWebView.callInjectedJavaScript();
                reactWebView.linkBridge();
                emitFinishEvent(webView, url);
            }
        }

        @Override
        public void onPageStarted(WebView webView, String url, Bitmap favicon) {
            super.onPageStarted(webView, url, favicon);
            mLastLoadFailed = false;

            dispatchEvent(
                    webView,
                    new TopLoadingStartEvent(
                            webView.getId(),
                            createWebViewEvent(webView, url)));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            boolean shouldOverrideUrlLoadingVal = ((ReactWebView) view).getShouldOverrideUrlLoadingVal();
            if(shouldOverrideUrlLoadingVal){
                dispatchEvent(view, new TopMessageEvent(view.getId(), "onShouldStartLoadWithRequest::" + url));
                return true;
            }else{
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    return false;
                }else{
                    return true;
                }
            }

//            if (url.startsWith("http://") || url.startsWith("https://") ||
//                    url.startsWith("file://")) {
//                return false;
//            } else {
////                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
////                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
////                view.getContext().startActivity(intent);
//                dispatchEvent(view, new TopMessageEvent(view.getId(), "onShouldStartLoadWithRequest::" + url));
//                return true;
//            }
        }

        @Override
        public void onReceivedError(
                WebView webView,
                int errorCode,
                String description,
                String failingUrl) {
            super.onReceivedError(webView, errorCode, description, failingUrl);
            mLastLoadFailed = true;

            // In case of an error JS side expect to get a finish event first, and then get an error event
            // Android WebView does it in the opposite way, so we need to simulate that behavior
            emitFinishEvent(webView, failingUrl);

            WritableMap eventData = createWebViewEvent(webView, failingUrl);
            eventData.putDouble("code", errorCode);
            eventData.putString("description", description);

            dispatchEvent(
                    webView,
                    new TopLoadingErrorEvent(webView.getId(), eventData));
        }

        @Override
        public void doUpdateVisitedHistory(WebView webView, String url, boolean isReload) {
            super.doUpdateVisitedHistory(webView, url, isReload);

            dispatchEvent(
                    webView,
                    new TopLoadingStartEvent(
                            webView.getId(),
                            createWebViewEvent(webView, url)));
        }

        private void emitFinishEvent(WebView webView, String url) {
            dispatchEvent(
                    webView,
                    new TopLoadingFinishEvent(
                            webView.getId(),
                            createWebViewEvent(webView, url)));
        }

        private WritableMap createWebViewEvent(WebView webView, String url) {
            WritableMap event = Arguments.createMap();
            event.putDouble("target", webView.getId());
            // Don't use webView.getUrl() here, the URL isn't updated to the new value yet in callbacks
            // like onPageFinished
            event.putString("url", url);
            event.putBoolean("loading", !mLastLoadFailed && webView.getProgress() != 100);
            event.putString("title", webView.getTitle());
            event.putBoolean("canGoBack", webView.canGoBack());
            event.putBoolean("canGoForward", webView.canGoForward());
            return event;
        }
    }

//    @Override
//    protected void onActivityResult(int requestCode, int resultCode,
//                                    Intent intent) {
//        if (requestCode == FILECHOOSER_RESULTCODE) {
//            if (null == mUploadMessage) return;
//            Uri result = intent == null || resultCode != RESULT_OK ? null
//                    : intent.getData();
//            mUploadMessage.onReceiveValue(result);
//            mUploadMessage = null;
//        }
//    }

    /**
     * Subclass of {@link WebView} that implements {@link LifecycleEventListener} interface in order
     * to call {@link WebView#destroy} on activty destroy event and also to clear the client
     */
    private static class ReactWebView extends WebView implements LifecycleEventListener {
        private @Nullable String injectedJS;
        private boolean messagingEnabled = false;
        private boolean shouldOverrideUrlLoadingVal = true;

        private class ReactWebViewBridge {
            ReactWebView mContext;

            ReactWebViewBridge(ReactWebView c) {
                mContext = c;
            }

            @JavascriptInterface
            public void send(String message) {
                mContext.onMessage(message);
            }

            @JavascriptInterface
            public void postMessage(String message) {
                mContext.onMessage(message);
            }
        }

        /**
         * WebView must be created with an context of the current activity
         *
         * Activity Context is required for creation of dialogs internally by WebView
         * Reactive Native needed for access to ReactNative internal system functionality
         *
         */
        public ReactWebView(ThemedReactContext reactContext) {
            super(reactContext);
        }

        @Override
        public void onHostResume() {
            // do nothing
        }

        @Override
        public void onHostPause() {
            // do nothing
        }

        @Override
        public void onHostDestroy() {
            cleanupCallbacksAndDestroy();
        }

        public void setInjectedJavaScript(@Nullable String js) {
            injectedJS = js;
        }

        public void setMessagingEnabled(boolean enabled) {
            if (messagingEnabled == enabled) {
                return;
            }

            messagingEnabled = enabled;
            if (enabled) {
                addJavascriptInterface(new ReactWebViewBridge(this), BRIDGE_NAME);
                linkBridge();
            } else {
                removeJavascriptInterface(BRIDGE_NAME);
            }
        }

        public void setShouldOverrideUrlLoadingVal(boolean enabled){
            shouldOverrideUrlLoadingVal = enabled;
        }

        public boolean getShouldOverrideUrlLoadingVal(){
            return shouldOverrideUrlLoadingVal;
        }

        public void callInjectedJavaScript() {
            if (getSettings().getJavaScriptEnabled() &&
                    injectedJS != null &&
                    !TextUtils.isEmpty(injectedJS)) {

                String js = injectedJS.replace("\r", "");
                js = js.replace("\n", "");
                loadUrl("javascript:(function() {\n" + js + ";\n})();");
            }
        }

        public void linkBridge() {
            if (messagingEnabled) {
                if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    // See isNative in lodash
                    String testPostMessageNative = "String(window.postMessage) === String(Object.hasOwnProperty).replace('hasOwnProperty', 'postMessage')";
                    evaluateJavascript(testPostMessageNative, new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            if (value.equals("true")) {
                                FLog.w(ReactConstants.TAG, "Setting onMessage on a WebView overrides existing values of window.postMessage, but a previous value was defined");
                            }
                        }
                    });
                }

                loadUrl("javascript:(" +
                        "window.originalPostMessage = window.postMessage," +
                        "window.postMessage = function(data) {" +
                        BRIDGE_NAME + ".postMessage(String(data));" +
                        "}" +
                        ")");
            }
        }

        public void onMessage(String message) {
            dispatchEvent(this, new TopMessageEvent(this.getId(), message));
        }

        private void cleanupCallbacksAndDestroy() {
            setWebViewClient(null);
            destroy();
        }
    }

    public WebViewBridgeManager(ReactApplicationContext reactApplicationContext) {
        mReactApplicationContext = reactApplicationContext;
        mWebViewConfig = new WebViewConfig() {
            public void configWebView(WebView webView) {
            }
        };

        mReactApplicationContext.addActivityEventListener(new ActivityEventListener() {
            @Override
            public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent intent) {
                if (requestCode == FILECHOOSER_RESULTCODE) {
                    if (null != mUploadMessage) {
                        Uri result = intent == null || resultCode != Activity.RESULT_OK ? null : intent.getData();
                        mUploadMessage.onReceiveValue(result);
                        mUploadMessage = null;
                    }
                    if (null != mUploadMessages) {
                        Uri result = intent == null || resultCode != Activity.RESULT_OK ? null : intent.getData();
                        if(result != null){
                            mUploadMessages.onReceiveValue(new Uri[]{result});
                        }else{
                            mUploadMessages.onReceiveValue(null);
                        }
                        mUploadMessages = null;
                    }
                }
            }

            @Override
            public void onNewIntent(Intent intent) {

            }
        });
    }

    public WebViewBridgeManager(WebViewConfig webViewConfig) {
        mWebViewConfig = webViewConfig;
    }

    @Override
    public String getName() {
        return REACT_CLASS;
    }


    @Override
    protected WebView createViewInstance(ThemedReactContext reactContext) {
        ReactWebView webView = new ReactWebView(reactContext);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, true, false);
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
                builder.setTitle("确认")
                        .setMessage(message)
                        .setPositiveButton("确定", null);
                // 不需要绑定按键事件
                // 屏蔽keycode等于84之类的按键
                builder.setOnKeyListener(new DialogInterface.OnKeyListener() {
                    public boolean onKey(DialogInterface dialog, int keyCode,KeyEvent event) {
                        Log.v("onJsAlert", "keyCode==" + keyCode + "event="+ event);
                        return true;
                    }
                });
                // 禁止响应按back键的事件
                builder.setCancelable(false);
                AlertDialog dialog = builder.create();
                dialog.show();
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                result.confirm();// 因为没有绑定事件，需要强行confirm,否则页面会变黑显示不了内容。
                return true;
            }

            /**
             * Tell the client to display a confirm dialog to the user. If the client
             * returns true, WebView will assume that the client will handle the
             * confirm dialog and call the appropriate JsResult method. If the
             * client returns false, a default value of false will be returned to
             * javascript. The default behavior is to return false.
             * @param view The WebView that initiated the callback.
             * @param url The url of the page requesting the dialog.
             * @param message Message to be displayed in the window.
             * @param result A JsResult used to send the user's response to
             *               javascript.
             * @return boolean Whether the client will handle the confirm dialog.
             */
            public boolean onJsConfirm(WebView view, String url, String message,
                                       final JsResult result) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
                builder.setTitle("确认")
                        .setMessage(message)
                        .setPositiveButton("确定",new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,int which) {
                                result.confirm();
                            }
                        })
                        .setNeutralButton("取消", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                result.cancel();
                            }
                        });
                builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        result.cancel();
                    }
                });

                // 屏蔽keycode等于84之类的按键，避免按键后导致对话框消息而页面无法再弹出对话框的问题
                builder.setOnKeyListener(new DialogInterface.OnKeyListener() {
                    @Override
                    public boolean onKey(DialogInterface dialog, int keyCode,KeyEvent event) {
                        Log.v("onJsConfirm", "keyCode==" + keyCode + "event="+ event);
                        return true;
                    }
                });
                // 禁止响应按back键的事件
                builder.setCancelable(false);
                AlertDialog dialog = builder.create();
                dialog.show();
                dialog.setCancelable(false);
                dialog.setCanceledOnTouchOutside(false);
                return true;
            }

            //关键代码，以下函数是没有API文档的，所以在Eclipse中会报错，如果添加了@Override关键字在这里的话。
            // For Android 3.0+
            public void openFileChooser(ValueCallback<Uri> uploadMsg) {

                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                WebViewBridgeManager.this.mReactApplicationContext.startActivityForResult(Intent.createChooser(i, "文件选择"), FILECHOOSER_RESULTCODE, null);
            }

            // For Android 3.0+
            public void openFileChooser(ValueCallback uploadMsg, String acceptType) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                WebViewBridgeManager.this.mReactApplicationContext.startActivityForResult(Intent.createChooser(i, "文件选择"), FILECHOOSER_RESULTCODE, null);
            }

            //For Android 4.1
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                mUploadMessage = uploadMsg;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                WebViewBridgeManager.this.mReactApplicationContext.startActivityForResult(Intent.createChooser(i, "文件选择"), FILECHOOSER_RESULTCODE, null);
            }

            //For Android 5
            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (mUploadMessage != null)
                    return false;
                mUploadMessages = filePathCallback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                WebViewBridgeManager.this.mReactApplicationContext.startActivityForResult(Intent.createChooser(i, "File Chooser"), FILECHOOSER_RESULTCODE, null);
                return true;
            }

        });
        reactContext.addLifecycleEventListener(webView);
        mWebViewConfig.configWebView(webView);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);

        // Fixes broken full-screen modals/galleries due to body height being 0.
        webView.setLayoutParams(
                new LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT));

        if (ReactBuildConfig.DEBUG && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        return webView;
    }

    @ReactProp(name = "javaScriptEnabled")
    public void setJavaScriptEnabled(WebView view, boolean enabled) {
        view.getSettings().setJavaScriptEnabled(enabled);
    }

    @ReactProp(name = "scalesPageToFit")
    public void setScalesPageToFit(WebView view, boolean enabled) {
        view.getSettings().setUseWideViewPort(!enabled);
    }

    @ReactProp(name = "domStorageEnabled")
    public void setDomStorageEnabled(WebView view, boolean enabled) {
        view.getSettings().setDomStorageEnabled(enabled);
    }

    @ReactProp(name = "userAgent")
    public void setUserAgent(WebView view, @Nullable String userAgent) {
        if (userAgent != null) {
            // TODO(8496850): Fix incorrect behavior when property is unset (uA == null)
            view.getSettings().setUserAgentString(userAgent);
        }
    }

    @ReactProp(name = "mediaPlaybackRequiresUserAction")
    public void setMediaPlaybackRequiresUserAction(WebView view, boolean requires) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            view.getSettings().setMediaPlaybackRequiresUserGesture(requires);
        }
    }

    @ReactProp(name = "injectedJavaScript")
    public void setInjectedJavaScript(WebView view, @Nullable String injectedJavaScript) {
        ((ReactWebView) view).setInjectedJavaScript(injectedJavaScript);
    }

    @ReactProp(name = "messagingEnabled")
    public void setMessagingEnabled(WebView view, boolean enabled) {
        ((ReactWebView) view).setMessagingEnabled(enabled);
    }

    @ReactProp(name = "source")
    public void setSource(WebView view, @Nullable ReadableMap source) {
        if (source != null) {
            if (source.hasKey("html")) {
                String html = source.getString("html");
                if (source.hasKey("baseUrl")) {
                    view.loadDataWithBaseURL(
                            source.getString("baseUrl"), html, HTML_MIME_TYPE, HTML_ENCODING, null);
                } else {
                    view.loadData(html, HTML_MIME_TYPE, HTML_ENCODING);
                }
                return;
            }
            if (source.hasKey("uri")) {
                String url = source.getString("uri");
                String previousUrl = view.getUrl();
                if (previousUrl != null && previousUrl.equals(url)) {
                    return;
                }
                if (source.hasKey("method")) {
                    String method = source.getString("method");
                    if (method.equals(HTTP_METHOD_POST)) {
                        byte[] postData = null;
                        if (source.hasKey("body")) {
                            String body = source.getString("body");
                            try {
                                postData = body.getBytes("UTF-8");
                            } catch (UnsupportedEncodingException e) {
                                postData = body.getBytes();
                            }
                        }
                        if (postData == null) {
                            postData = new byte[0];
                        }
                        view.postUrl(url, postData);
                        return;
                    }
                }
                HashMap<String, String> headerMap = new HashMap<>();
                if (source.hasKey("headers")) {
                    ReadableMap headers = source.getMap("headers");
                    ReadableMapKeySetIterator iter = headers.keySetIterator();
                    while (iter.hasNextKey()) {
                        String key = iter.nextKey();
                        headerMap.put(key, headers.getString(key));
                    }
                }
                view.loadUrl(url, headerMap);
                return;
            }
        }
        view.loadUrl(BLANK_URL);
    }

    @ReactProp(name = "onContentSizeChange")
    public void setOnContentSizeChange(WebView view, boolean sendContentSizeChangeEvents) {
        if (sendContentSizeChangeEvents) {
            view.setPictureListener(getPictureListener());
        } else {
            view.setPictureListener(null);
        }
    }

    @ReactProp(name = "shouldOverrideUrlLoadingVal")
    public void setShouldOverrideUrlLoadingVal(WebView view, boolean enabled) {
        ((ReactWebView) view).setShouldOverrideUrlLoadingVal(enabled);
    }

    @Override
    protected void addEventEmitters(ThemedReactContext reactContext, WebView view) {
        // Do not register default touch emitter and let WebView implementation handle touches
        view.setWebViewClient(new ReactWebViewClient());
    }

    @Override
    public @Nullable Map<String, Integer> getCommandsMap() {
        return MapBuilder.of(
                "goBack", COMMAND_GO_BACK,
                "goForward", COMMAND_GO_FORWARD,
                "reload", COMMAND_RELOAD,
                "stopLoading", COMMAND_STOP_LOADING,
                "postMessage", COMMAND_POST_MESSAGE);
    }

    @Override
    public void receiveCommand(WebView root, int commandId, @Nullable ReadableArray args) {
        switch (commandId) {
            case COMMAND_GO_BACK:
                root.goBack();
                break;
            case COMMAND_GO_FORWARD:
                root.goForward();
                break;
            case COMMAND_RELOAD:
                root.reload();
                break;
            case COMMAND_STOP_LOADING:
                root.stopLoading();
                break;
            case COMMAND_POST_MESSAGE:
                String message = args.getString(0);
                if(message != null){
                    String js = String.format("javascript:(WebViewBridge.onMessage&&WebViewBridge.onMessage('%s'));", message);
                    root.loadUrl(js);
                }
                break;
        }
    }

    @Override
    public void onDropViewInstance(WebView webView) {
        super.onDropViewInstance(webView);
        ((ThemedReactContext) webView.getContext()).removeLifecycleEventListener((ReactWebView) webView);
        ((ReactWebView) webView).cleanupCallbacksAndDestroy();
    }

    private WebView.PictureListener getPictureListener() {
        if (mPictureListener == null) {
            mPictureListener = new WebView.PictureListener() {
                @Override
                public void onNewPicture(WebView webView, Picture picture) {
                    dispatchEvent(
                            webView,
                            new ContentSizeChangeEvent(
                                    webView.getId(),
                                    webView.getWidth(),
                                    webView.getContentHeight()));
                }
            };
        }
        return mPictureListener;
    }

    private static void dispatchEvent(WebView webView, Event event) {
        ReactContext reactContext = (ReactContext) webView.getContext();
        EventDispatcher eventDispatcher =
                reactContext.getNativeModule(UIManagerModule.class).getEventDispatcher();
        eventDispatcher.dispatchEvent(event);
    }



    @ReactProp(name = "allowFileAccessFromFileURLs")
    public void setAllowFileAccessFromFileURLs(WebView root, boolean allows) {
        root.getSettings().setAllowFileAccessFromFileURLs(allows);
    }

    @ReactProp(name = "allowUniversalAccessFromFileURLs")
    public void setAllowUniversalAccessFromFileURLs(WebView root, boolean allows) {
        root.getSettings().setAllowUniversalAccessFromFileURLs(allows);
    }
}
