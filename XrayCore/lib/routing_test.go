package lib

import (
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"reflect"
	"strconv"
	"testing"
	"time"

	"github.com/xtls/xray-core/app/proxyman"
	outboundmanager "github.com/xtls/xray-core/app/proxyman/outbound"
	statscommand "github.com/xtls/xray-core/app/stats/command"
	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/features/outbound"
	"github.com/xtls/xray-core/features/stats"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

type routingTestOutbound struct {
	outbound.Handler
	tag string
}

func (h *routingTestOutbound) Tag() string { return h.tag }

func TestStagedVersionsAreInvisibleToOldPrefixBalancers(t *testing.T) {
	manager, err := outboundmanager.New(context.Background(), &proxyman.OutboundConfig{})
	if err != nil {
		t.Fatal(err)
	}
	original := &routingTestOutbound{tag: "member.a"}
	alias := &outboundAlias{Handler: original, tag: "_yaxc.live.synthetic.1.0."}
	version := &exactOutboundHandler{Handler: &routingTestOutbound{tag: "_yaxc.live.synthetic.2.0."}}
	for _, handler := range []outbound.Handler{original, alias, version} {
		if err := manager.AddHandler(context.Background(), handler); err != nil {
			t.Fatal(err)
		}
	}
	for _, selector := range []string{"", "member.", "member.a"} {
		if got := manager.Select([]string{selector}); !reflect.DeepEqual(got, []string{"member.a"}) {
			t.Fatalf("old selector %q exposed staged versions: %v", selector, got)
		}
	}
	for _, handler := range []outbound.Handler{alias, version} {
		if got := manager.Select([]string{handler.Tag()}); !reflect.DeepEqual(got, []string{handler.Tag()}) {
			t.Fatalf("exact version selector failed: %v", got)
		}
	}
}

func TestLiveDialerReferencesDoNotRewriteHeaders(t *testing.T) {
	document, err := decodeRoutingDocument([]byte(`{
		"streamSettings":{
			"sockopt":{"dialerProxy":"outer"},
			"xhttpSettings":{
				"headers":{"dialerProxy":"ordinary-header","proxySettings":"another-header"},
				"extra":{"headers":{"dialerProxy":"untouched"},"downloadSettings":{
					"sockopt":{"dialerProxy":"download"},
					"splithttpSettings":{"downloadSettings":{"sockopt":{"dialerProxy":"nested"}}}
				}}
			}
		}
	}`))
	if err != nil {
		t.Fatal(err)
	}
	var references []string
	if err := outboundDependencies(document, func(tag string) error {
		references = append(references, tag)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(references, []string{"outer", "download", "nested"}) {
		t.Fatalf("wrong graph references: %v", references)
	}
	entries := map[string]*liveOutbound{
		"outer":    {physical: "version.outer."},
		"download": {physical: "version.download."},
		"nested":   {physical: "version.nested."},
	}
	rewriteOutboundDependencies(document, entries)
	stream := object(document["streamSettings"])
	settings := object(stream["xhttpSettings"])
	if object(settings["headers"])["dialerProxy"] != "ordinary-header" || object(object(settings["extra"])["headers"])["dialerProxy"] != "untouched" {
		t.Fatal("ordinary HTTP headers were rewritten as outbound references")
	}
	references = nil
	if err := outboundDependencies(document, func(tag string) error {
		references = append(references, tag)
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(references, []string{"version.outer.", "version.download.", "version.nested."}) {
		t.Fatalf("nested outbound graph was not fully versioned: %v", references)
	}
}

func TestServiceRegisteredApiTargetKeepsItsCounterAndListenerThroughApply(t *testing.T) {
	dir := t.TempDir()
	t.Setenv(platform.AssetLocation, dir)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	document := map[string]any{
		"log":       map[string]any{"loglevel": "none"},
		"api":       map[string]any{"tag": "api-service", "services": []string{"StatsService"}},
		"stats":     map[string]any{},
		"inbounds":  []any{map[string]any{"tag": "api-in", "listen": "127.0.0.1", "port": port, "protocol": "dokodemo-door", "settings": map[string]any{"address": "127.0.0.1"}}},
		"outbounds": []any{map[string]any{"tag": "direct", "protocol": "freedom"}},
		"routing":   map[string]any{"rules": []any{map[string]any{"inboundTag": []string{"api-in"}, "outboundTag": "api-service"}}},
	}
	encoded, err := json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(dir, "core.json")
	if err := os.WriteFile(file, encoded, 0600); err != nil {
		t.Fatal(err)
	}
	if err := Start(dir, file); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := Stop(); err != nil {
			t.Error(err)
		}
	})
	counter, err := coreServer.GetFeature(stats.ManagerType()).(stats.Manager).RegisterCounter("live-sentinel")
	if err != nil {
		t.Fatal(err)
	}
	counter.Add(42)
	requireCounter := func() {
		t.Helper()
		client, err := grpc.NewClient(net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), grpc.WithTransportCredentials(insecure.NewCredentials()))
		if err != nil {
			t.Fatal(err)
		}
		defer client.Close()
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		defer cancel()
		response, err := statscommand.NewStatsServiceClient(client).GetStats(ctx, &statscommand.GetStatsRequest{Name: "live-sentinel"})
		if err != nil || response.GetStat().GetValue() != 42 {
			t.Fatalf("service handler lost its real counter: response=%v err=%v", response, err)
		}
	}
	requireCounter()
	routing := document["routing"].(map[string]any)
	routing["rules"] = append(routing["rules"].([]any), map[string]any{"domain": []string{"full:unrelated.invalid"}, "outboundTag": "direct"})
	encoded, err = json.Marshal(document)
	if err != nil {
		t.Fatal(err)
	}
	if err := ApplyRouting(string(encoded)); err != nil {
		t.Fatal(err)
	}
	requireCounter()
}
