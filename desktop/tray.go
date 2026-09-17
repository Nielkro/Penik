package main

import (
	_ "embed"

	"github.com/getlantern/systray"
)

//go:embed build/trayicon.png
var trayIconBytes []byte

func initSystemTray(app *App) {
	onReady := func() {
		systray.SetIcon(trayIconBytes)
		systray.SetTitle("Penik")
		systray.SetTooltip("Penik Messenger")

		mShow := systray.AddMenuItem("Открыть Penik", "Показать главное окно")
		systray.AddSeparator()
		mQuit := systray.AddMenuItem("Выход", "Закрыть приложение")

		go func() {
			for range mShow.ClickedCh {
				app.Show()
			}
		}()

		go func() {
			for range mQuit.ClickedCh {
				app.Quit()
			}
		}()
	}

	onExit := func() {
		// Cleanup when exiting
	}

	systray.Register(onReady, onExit)
}
