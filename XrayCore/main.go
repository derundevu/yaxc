package XrayCore

import (
	"XrayCore/lib"
	"github.com/xtls/libxray/nodep"
	"github.com/xtls/libxray/share"
	"github.com/xtls/xray-core/infra/conf"
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

func Share(config string) string {
	links, err := share.ConvertXrayJsonToShareLinks([]byte(config))
	if err != nil {
		return ""
	}
	return links
}
