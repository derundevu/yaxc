package XrayCore

import (
	"XrayCore/lib"
	"github.com/xtls/libxray/nodep"
	"github.com/xtls/libxray/share"
	"github.com/xtls/xray-core/infra/conf"
)

// ValidateInWorker fully constructs and closes a candidate core without starting
// listeners. Callers must isolate it in a dedicated process: construction and
// Close replace process-global dialer and logger state. An app-owned active core
// is rejected, but cores created by other libraries cannot be detected here.
// It returns an empty string for valid configurations or the native diagnostic.
func ValidateInWorker(dir string, config string) string {
	err := lib.ValidateInWorker(dir, config)
	return lib.WrapError(err)
}

func Start(dir string, config string) string {
	err := lib.Start(dir, config)
	return lib.WrapError(err)
}

// ApplyRouting atomically replaces routing rules/balancers and versions changed
// outbounds without restarting the core or closing established connections.
// configText is the full generated JSON, not a path. Empty means success.
func ApplyRouting(configText string) string {
	return lib.WrapError(lib.ApplyRouting(configText))
}

// LiveRoutingStatus reports only runtime group identities and health state.
func LiveRoutingStatus() string {
	return lib.LiveRoutingStatus()
}

func Stop() string {
	err := lib.Stop()
	return lib.WrapError(err)
}

func Version() string {
	return lib.Version()
}

func Json(link string) string {
	var response nodep.CallResponse[*conf.Config]
	xrayJson, err := share.ConvertShareLinksToXrayJson(link)
	return response.EncodeToBase64(xrayJson, err)
}

func Share(config string) string {
	links, err := share.ConvertXrayJsonToShareLinks([]byte(config))
	if err != nil {
		return ""
	}
	return links
}
