package lib

import (
	"errors"
	"os"
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
	content, err := os.ReadFile(config)
	if err != nil {
		return err
	}
	document, err := decodeRoutingDocument(content)
	if err != nil {
		return err
	}
	configJSON, err := loadConfig(config)
	if err != nil {
		return err
	}
	if err := prepareLiveCoreConfig(configJSON, document); err != nil {
		return err
	}
	server, err := core.New(configJSON)
	if err != nil {
		return err
	}
	state, err := newRoutingState(document, server, configJSON)
	if err != nil {
		return errors.Join(err, server.Close())
	}
	connections, err := newConnectionLedger(server, document, state)
	if err != nil {
		return errors.Join(err, server.Close())
	}
	state.connections = connections
	health, healthPlan, err := prepareLiveHealth(server, document, state.outbounds, nil)
	if err != nil {
		return errors.Join(err, server.Close())
	}
	if health != nil {
		if err := bindInitialHealth(server, configJSON, healthPlan); err != nil {
			health.close()
			return errors.Join(err, server.Close())
		}
	}
	if err := server.Start(); err != nil {
		if health != nil {
			health.close()
		}
		closeErr := server.Close()
		connections.stop()
		return errors.Join(err, closeErr)
	}
	coreServer = server
	routingState = state
	state.health = health
	connections.started()
	currentConnections.Store(connections)
	if health != nil {
		health.replace(healthPlan)
		health.start()
	}
	return nil
}

func Stop() error {
	lifecycleMu.Lock()
	defer lifecycleMu.Unlock()
	if coreServer != nil {
		if routingState != nil && routingState.health != nil {
			routingState.health.close()
		}
		err := coreServer.Close()
		if routingState != nil && routingState.connections != nil {
			routingState.connections.stop()
		}
		coreServer = nil
		routingState = nil
		if err != nil {
			return err
		}
	}
	return nil
}

func Version() string {
	return core.Version()
}
