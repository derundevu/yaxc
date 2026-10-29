package lib

import (
	"os"

	"github.com/xtls/xray-core/common/platform"
)

func SetEnv(dir string) {
	os.Setenv(platform.AssetLocation, dir)
}
