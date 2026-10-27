package XrayCore

import (
	"context"
	"errors"
	"sync"

	"github.com/xtls/libxray/nodep"
	"github.com/xtls/libxray/xray"
)

type pingRequest struct {
	ctx    context.Context
	cancel context.CancelFunc
}

var pingRequests = struct {
	sync.Mutex
	requests map[int64]pingRequest
}{requests: make(map[int64]pingRequest)}

// A worker process must never run two cores: Xray's dialer and logger are global.
var pingCoreLock sync.Mutex

// PreparePing registers cancellation before a request is queued for execution.
func PreparePing(id int64) {
	pingRequests.Lock()
	defer pingRequests.Unlock()
	if request, exists := pingRequests.requests[id]; exists {
		request.cancel()
	}
	ctx, cancel := context.WithCancel(context.Background())
	pingRequests.requests[id] = pingRequest{ctx: ctx, cancel: cancel}
}

func CancelPing(id int64) {
	pingRequests.Lock()
	defer pingRequests.Unlock()
	if request, exists := pingRequests.requests[id]; exists {
		request.cancel()
	}
}

func PingPrepared(id int64, dir string, config string, timeout int, url string, proxy string, pingType string) string {
	var response nodep.CallResponse[int64]
	pingRequests.Lock()
	request, exists := pingRequests.requests[id]
	pingRequests.Unlock()
	if !exists {
		return response.EncodeToBase64(nodep.PingDelayError, errors.New("ping request is not prepared"))
	}
	defer func() {
		request.cancel()
		pingRequests.Lock()
		delete(pingRequests.requests, id)
		pingRequests.Unlock()
	}()

	pingCoreLock.Lock()
	defer pingCoreLock.Unlock()
	delay, err := xray.PingWithContext(request.ctx, dir, config, timeout, url, proxy, pingType)
	return response.EncodeToBase64(delay, err)
}
