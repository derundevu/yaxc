package XrayCore_test

import (
	"encoding/json"
	"net/url"
	"strings"
	"testing"

	xray "XrayCore"

	"github.com/xtls/xray-core/infra/conf"
)

func TestMigrationHysteriaBandwidthAndHoppingRoundtrip(t *testing.T) {
	input := `proxies:
  - name: synthetic-hy
    type: hysteria2
    server: 127.0.0.1
    port: 443
    password: "auth:p@ss"
    tls: true
    sni: example.test
    up: "8000000 bps"
    down: "16000000 bps"
    ports: "443,8443-8444"
    hop-interval: 17
    obfs: salamander
    obfs-password: "mask:pass"
`
	config := importShare(t, input)
	exported := xray.Share(string(config))
	for _, data := range [][]byte{config, importShare(t, exported)} {
		var parsed conf.Config
		if err := json.Unmarshal(data, &parsed); err != nil {
			t.Fatal(err)
		}
		if len(parsed.OutboundConfigs) != 1 || parsed.OutboundConfigs[0].StreamSetting == nil {
			t.Fatal("Hysteria import lost its outbound/transport")
		}
		mask := parsed.OutboundConfigs[0].StreamSetting.FinalMask
		if mask == nil || mask.QuicParams == nil {
			t.Fatal("Hysteria bandwidth limits disappeared")
		}
		up, upErr := mask.QuicParams.BrutalUp.Bps()
		down, downErr := mask.QuicParams.BrutalDown.Bps()
		if upErr != nil || downErr != nil || up != 1_000_000 || down != 2_000_000 {
			t.Fatalf("Hysteria bandwidth changed: up=%d down=%d errors=%v/%v", up, down, upErr, downErr)
		}
		if len(mask.Udp) != 2 || mask.Udp[0].Type != "salamander" || mask.Udp[1].Type != "udphop" {
			t.Fatal("Hysteria mask/hopping order changed")
		}
		if mask.Udp[1].Settings == nil {
			t.Fatal("Hysteria hopping settings disappeared")
		}
		var hop conf.UDPHop
		if err := json.Unmarshal(*mask.Udp[1].Settings, &hop); err != nil {
			t.Fatal(err)
		}
		ports := hop.RemotePorts.Build().Ports()
		if hop.Mode != "intervalLocal,intervalRemote" || hop.Interval.From != 17 || hop.Interval.To != 17 || len(ports) != 3 || ports[0] != 443 || ports[1] != 8443 || ports[2] != 8444 {
			t.Fatalf("Hysteria hopping semantics changed: %+v, ports=%v", hop, ports)
		}
	}
}

func TestMigrationXHTTPExtraKeepsSessionAndProviderOptions(t *testing.T) {
	extra := `{"sessionPlacement":"header","sessionKey":"X-Custom-Session","uplinkChunkSize":3072,"providerOption":{"token":"keep"}}`
	query := url.Values{"encryption": {"none"}, "type": {"xhttp"}, "extra": {extra}}
	input := syntheticShare("vless", "00000000-0000-4000-8000-000000000001", "127.0.0.1", "443", "synthetic-xhttp", query)
	config := importShare(t, input)
	for _, data := range [][]byte{config, importShare(t, xray.Share(string(config)))} {
		requireJSONFields(t, data, []byte(`{"outbounds":[{"streamSettings":{"xhttpSettings":{"extra":{"sessionIDPlacement":"header","sessionIDKey":"X-Custom-Session","uplinkChunkSize":3072,"providerOption":{"token":"keep"}}}}}]}`))
	}
}

func TestMigrationRemovedShadowsocksCapabilitiesDoNotDropNodesSilently(t *testing.T) {
	valid := "  - {name: valid, type: ss, server: 127.0.0.1, port: 8388, cipher: aes-128-gcm, password: secret}\n"
	for name, removed := range map[string]string{
		"UDP-over-TCP":     "  - {name: removed, type: ss, server: 127.0.0.1, port: 8389, cipher: aes-128-gcm, password: secret, udp-over-tcp: true}\n",
		"cleartext cipher": "  - {name: removed, type: ss, server: 127.0.0.1, port: 8389, cipher: none, password: secret}\n",
	} {
		t.Run(name, func(t *testing.T) {
			response := decodeShareResponse(t, xray.Json("proxies:\n"+valid+removed))
			if response.Success || !strings.Contains(strings.ToLower(response.Error), "shadowsocks") {
				t.Fatalf("migration returned a successful subset instead of an unsupported-capability error: %+v", response)
			}
		})
	}
}
