package lib

import (
	"errors"
	"sync"

	"github.com/xtls/xray-core/common/cmdarg"
	"github.com/xtls/xray-core/core"
	_ "github.com/xtls/xray-core/main/distro/all"
)

var (
	lifecycleMu sync.Mutex
	coreServer  *core.Instance
)

func loadConfig(config string) (*core.Config, error) {
	return core.LoadConfig("json", cmdarg.Arg{config})
}

func Server(config string) (*core.Instance, error) {
	json, err := loadConfig(config)
	if err != nil {
		return nil, err
	}
	return core.New(json)
}

func Start(dir string, config string) error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if coreServer != nil {
		return errors.New("Xray is already running")
	}
	SetEnv(dir)
	server, err := Server(config)
	if err != nil {
		return err
	}
	if err := server.Start(); err != nil {
		return errors.Join(err, server.Close())
	}
	coreServer = server
	return nil
}

func Stop() error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if coreServer != nil {
		err := coreServer.Close()
		coreServer = nil
		if err != nil {
			return err
		}
	}
	return nil
}

func Version() string {
	return core.Version()
}
