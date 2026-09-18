//go:build linux && webkit2_41

package main

/*
#cgo linux pkg-config: gtk+-3.0 webkit2gtk-4.1

#include <gtk/gtk.h>
#include <webkit2/webkit2.h>
#include <stdio.h>
#include <string.h>

static gboolean on_permission_request(WebKitWebView *web_view, WebKitPermissionRequest *request, gpointer user_data) {
	if (request != NULL) {
		printf("[penik-webkit] Auto-allowing permission request\n");
		webkit_permission_request_allow(request);
		return TRUE;
	}
	return FALSE;
}

static void enable_all_webrtc_features(WebKitSettings *settings) {
	if (!settings) return;
	webkit_settings_set_enable_webrtc(settings, TRUE);
	webkit_settings_set_enable_media_stream(settings, TRUE);
	webkit_settings_set_enable_mediasource(settings, TRUE);
	webkit_settings_set_enable_developer_extras(settings, TRUE);
	webkit_settings_set_disable_web_security(settings, TRUE);
	webkit_settings_set_allow_universal_access_from_file_urls(settings, TRUE);

	WebKitFeatureList *features = webkit_settings_get_all_features();
	if (features) {
		gsize len = webkit_feature_list_get_length(features);
		for (gsize i = 0; i < len; i++) {
			WebKitFeature *f = webkit_feature_list_get(features, i);
			const char *id = webkit_feature_get_identifier(f);
			if (id && (strstr(id, "webrtc") || strstr(id, "media") || strstr(id, "peer") || strstr(id, "stream") || strstr(id, "rtc"))) {
				webkit_settings_set_feature_enabled(settings, f, TRUE);
				printf("[penik-webkit] Enabled WebKit feature: %s\n", id);
			}
		}
		webkit_feature_list_unref(features);
	}
}

static void configure_webview_widget(GtkWidget *widget, gpointer data) {
	if (WEBKIT_IS_WEB_VIEW(widget)) {
		WebKitWebView *webview = WEBKIT_WEB_VIEW(widget);
		gpointer configured = g_object_get_data(G_OBJECT(webview), "penik-webrtc-configured");
		if (!configured) {
			g_object_set_data(G_OBJECT(webview), "penik-webrtc-configured", GINT_TO_POINTER(1));
			WebKitSettings *settings = webkit_web_view_get_settings(webview);
			enable_all_webrtc_features(settings);
			g_signal_connect(webview, "permission-request", G_CALLBACK(on_permission_request), NULL);
			printf("[penik-webkit] Configured WebKitWebView and reloading WebProcess for WebRTC...\n");
			webkit_web_view_reload(webview);
		}
	} else if (GTK_IS_CONTAINER(widget)) {
		GList *children = gtk_container_get_children(GTK_CONTAINER(widget));
		for (GList *c = children; c != NULL; c = c->next) {
			configure_webview_widget(GTK_WIDGET(c->data), data);
		}
		if (children) {
			g_list_free(children);
		}
	}
}

static void configure_all_webviews() {
	GList *toplevels = gtk_window_list_toplevels();
	for (GList *l = toplevels; l != NULL; l = l->next) {
		if (GTK_IS_WINDOW(l->data)) {
			configure_webview_widget(GTK_WIDGET(l->data), NULL);
		}
	}
	if (toplevels) {
		g_list_free(toplevels);
	}
}

static gboolean configure_webviews_idle(gpointer data) {
	configure_all_webviews();
	return G_SOURCE_REMOVE;
}

void InitLinuxWebKitEarly() {
	WebKitWebContext *context = webkit_web_context_get_default();
	if (context) {
		WebKitSecurityManager *sec = webkit_web_context_get_security_manager(context);
		if (sec) {
			webkit_security_manager_register_uri_scheme_as_secure(sec, "wails");
			webkit_security_manager_register_uri_scheme_as_cors_enabled(sec, "wails");
			webkit_security_manager_register_uri_scheme_as_local(sec, "wails");
			printf("[penik-webkit] WebKitSecurityManager initialized for wails://\n");
		}
	}
}

void SetupLinuxWebKit() {
	InitLinuxWebKitEarly();
	g_idle_add(configure_webviews_idle, NULL);
}
*/
import "C"

func init() {
	C.InitLinuxWebKitEarly()
}

func setupPlatformWebview() {
	C.SetupLinuxWebKit()
}
