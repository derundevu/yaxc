package XrayCore

import (
	"github.com/xtls/libxray/nodep"
	"github.com/xtls/libxray/share"
	"github.com/xtls/libxray/xray"
	"github.com/xtls/xray-core/infra/conf"
	"XrayCore/lib"
)

func Test(dir string, config string) string {
	err := lib.Test(dir, config)
	return lib.WrapError(err)
}

func Start(dir string, config string) string {
	err := lib.Start(dir, config)
	return lib.WrapError(err)
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

func Ping(dir string, config string, timeout int, url string, proxy string) string {
	var response nodep.CallResponse[int64]
	delay, err := xray.Ping(dir, config, timeout, url, proxy)
	return response.EncodeToBase64(delay, err)
}
