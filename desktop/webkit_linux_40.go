//go:build linux && !webkit2_41

package main

/*
#cgo linux pkg-config: gtk+-3.0 webkit2gtk-4.0

#include <gtk/gtk.h>
#include <webkit2/webkit2.h>

static gboolean on_permission_request(WebKitWebView *web_view, WebKitPermissionRequest *request, gpointer user_data) {
	webkit_permission_request_allow(request);
	return TRUE;
}

static void configure_webview_widget(GtkWidget *widget, gpointer data) {
	if (WEBKIT_IS_WEB_VIEW(widget)) {
		WebKitWebView *webview = WEBKIT_WEB_VIEW(widget);
		WebKitSettings *settings = webkit_web_view_get_settings(webview);
		if (settings) {
			webkit_settings_set_enable_webrtc(settings, TRUE);
			webkit_settings_set_enable_media_stream(settings, TRUE);
			webkit_settings_set_enable_mediasource(settings, TRUE);
			webkit_settings_set_enable_developer_extras(settings, TRUE);
		}
		g_signal_connect(webview, "permission-request", G_CALLBACK(on_permission_request), NULL);
	} else if (GTK_IS_CONTAINER(widget)) {
		gtk_container_forall(GTK_CONTAINER(widget), configure_webview_widget, data);
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

void SetupLinuxWebKit() {
	g_idle_add(configure_webviews_idle, NULL);
}
*/
import "C"

func setupPlatformWebview() {
	C.SetupLinuxWebKit()
}
