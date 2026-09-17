package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

const (
	DefaultServerURL = "https://api.penik.ru"
	ConfigDirName    = "penik"
	ConfigFileName   = "config.json"
)

type Config struct {
	ServerURL      string `json:"server_url"`
	MinimizeToTray bool   `json:"minimize_to_tray"`
	StartMinimized bool   `json:"start_minimized"`
	AutoStart      bool   `json:"auto_start"`
}

var (
	configMu sync.RWMutex
	currentConfig *Config
)

func getDefaultConfig() *Config {
	return &Config{
		ServerURL:      DefaultServerURL,
		MinimizeToTray: true,
		StartMinimized: false,
		AutoStart:      false,
	}
}

func getConfigPath() (string, error) {
	userConfigDir, err := os.UserConfigDir()
	if err != nil {
		userConfigDir, err = os.UserHomeDir()
		if err != nil {
			return "", err
		}
		userConfigDir = filepath.Join(userConfigDir, ".config")
	}
	dir := filepath.Join(userConfigDir, ConfigDirName)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", err
	}
	return filepath.Join(dir, ConfigFileName), nil
}

func LoadConfig() (*Config, error) {
	configMu.Lock()
	defer configMu.Unlock()

	if currentConfig != nil {
		return currentConfig, nil
	}

	cfgPath, err := getConfigPath()
	if err != nil {
		currentConfig = getDefaultConfig()
		return currentConfig, nil
	}

	data, err := os.ReadFile(cfgPath)
	if err != nil {
		// If file doesn't exist, create default
		cfg := getDefaultConfig()
		currentConfig = cfg
		_ = saveConfigLocked(cfg, cfgPath)
		return cfg, nil
	}

	cfg := getDefaultConfig()
	if err := json.Unmarshal(data, cfg); err != nil {
		currentConfig = cfg
		return cfg, nil
	}

	if cfg.ServerURL == "" {
		cfg.ServerURL = DefaultServerURL
	}

	currentConfig = cfg
	return cfg, nil
}

func SaveConfig(cfg *Config) error {
	configMu.Lock()
	defer configMu.Unlock()

	cfgPath, err := getConfigPath()
	if err != nil {
		return err
	}

	currentConfig = cfg
	return saveConfigLocked(cfg, cfgPath)
}

func saveConfigLocked(cfg *Config, path string) error {
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}
