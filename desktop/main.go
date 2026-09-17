package main

import (
	"embed"
	"log"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/menu"
	"github.com/wailsapp/wails/v2/pkg/menu/keys"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/linux"
	"github.com/wailsapp/wails/v2/pkg/options/windows"
)

//go:embed all:frontend/dist
var assets embed.FS

//go:embed build/appicon.png
var icon []byte

func main() {
	app := NewApp()

	// Application Tray menu
	trayMenu := menu.NewMenu()
	trayMenu.AddText("Open Penik", keys.CmdOrCtrl("o"), func(_ *menu.CallbackData) {
		app.Show()
	})
	trayMenu.AddSeparator()
	trayMenu.AddText("Quit", keys.CmdOrCtrl("q"), func(_ *menu.CallbackData) {
		app.Quit()
	})

	err := wails.Run(&options.App{
		Title:             "Penik",
		Width:             1200,
		Height:            800,
		MinWidth:          360,
		MinHeight:         600,
		DisableResize:     false,
		Fullscreen:        false,
		Frameless:         false,
		StartHidden:       app.config != nil && app.config.StartMinimized,
		HideWindowOnClose: app.config != nil && app.config.MinimizeToTray,
		BackgroundColour:  &options.RGBA{R: 13, G: 13, B: 18, A: 255},
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		OnStartup:     app.startup,
		OnBeforeClose: app.beforeClose,
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId: "penik-desktop-single-instance",
			OnSecondInstanceLaunch: func(secondInstanceData options.SecondInstanceData) {
				app.Show()
			},
		},
		Bind: []interface{}{
			app,
		},
		Windows: &windows.Options{
			WebviewIsTransparent: false,
			WindowIsTranslucent:  false,
			DisableWindowIcon:    false,
		},
		Linux: &linux.Options{
			Icon:                icon,
			WindowIsTranslucent: false,
			WebviewGpuPolicy:    linux.WebviewGpuPolicyAlways,
		},
	})

	if err != nil {
		log.Fatalf("Error running Penik desktop application: %v", err)
	}
}
