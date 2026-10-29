package lib

import (
	"errors"
	"os"

	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
)

// ValidateInWorker loads the complete configuration and referenced resources,
// constructs a core, and closes it without Start. The caller must use a dedicated
// process because construction and Close mutate process-global dialer and logger
// state. The guard covers only this package's coreServer, not external cores.
func ValidateInWorker(dir string, config string) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if coreServer != nil {
		return errors.New("cannot validate while Xray is running; use an isolated worker process")
	}
	previousDir, hadDir := os.LookupEnv(platform.AssetLocation)
	defer func() {
		if hadDir {
			os.Setenv(platform.AssetLocation, previousDir)
		} else {
			os.Unsetenv(platform.AssetLocation)
		}
	}()
	SetEnv(dir)
	configJSON, err := loadConfig(config)
	if err != nil {
		return err
	}
	server, err := core.New(configJSON)
	if err != nil {
		return err
	}
	return server.Close()
}
