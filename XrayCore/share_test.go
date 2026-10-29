package XrayCore_test

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"reflect"
	"strings"
	"testing"

	xray "XrayCore"
)

type shareResponse struct {
	Success bool            `json:"success"`
	Data    json.RawMessage `json:"data"`
	Error   string          `json:"error"`
}

func decodeShareResponse(t *testing.T, encoded string) shareResponse {
	t.Helper()
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatalf("Json returned an invalid Base64 response: %v", err)
	}
	var response shareResponse
	if err := json.Unmarshal(decoded, &response); err != nil {
		t.Fatalf("Json returned an invalid response envelope: %v", err)
	}
	return response
}

func importShare(t *testing.T, text string) json.RawMessage {
	t.Helper()
	response := decodeShareResponse(t, xray.Json(text))
	if !response.Success || response.Error != "" {
		t.Fatalf("Json rejected synthetic share input: %s", response.Error)
	}
	if !json.Valid(response.Data) || string(response.Data) == "null" {
		t.Fatalf("Json succeeded without a configuration: %s", response.Data)
	}
	return response.Data
}

// Only compare supported, consumer-visible fields. Typed native conversion is not
// a lossless JSON editor, and its default/omitted fields are not the contract.
func requireJSONFields(t *testing.T, actual, expected []byte) {
	t.Helper()
	var got, want any
	if err := json.Unmarshal(actual, &got); err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(expected, &want); err != nil {
		t.Fatal(err)
	}
	requireJSONValue(t, "$", got, want)
}

func requireJSONValue(t *testing.T, path string, got, want any) {
	t.Helper()
	switch want := want.(type) {
	case map[string]any:
		object, ok := got.(map[string]any)
		if !ok {
			t.Fatalf("%s: expected an object, got %#v", path, got)
		}
		for key, value := range want {
			actual, exists := object[key]
			// REALITY accepts both names; password is the effective key when set.
			if strings.HasSuffix(path, ".realitySettings") && key == "publicKey" {
				if password, present := object["password"]; present && password != "" {
					actual, exists = password, true
				}
			}
			if !exists {
				t.Fatalf("%s.%s: field missing", path, key)
			}
			requireJSONValue(t, path+"."+key, actual, value)
		}
	case []any:
		array, ok := got.([]any)
		if !ok || len(array) != len(want) {
			t.Fatalf("%s: expected %d array entries, got %#v", path, len(want), got)
		}
		for i, value := range want {
			requireJSONValue(t, fmt.Sprintf("%s[%d]", path, i), array[i], value)
		}
	case string:
		actual, ok := got.(string)
		if strings.HasSuffix(path, ".streamSettings.network") {
			actual, want = canonicalTransport(actual), canonicalTransport(want)
		}
		if !ok || actual != want {
			t.Fatalf("%s: got %#v, want %#v", path, got, want)
		}
	default:
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("%s: got %#v, want %#v", path, got, want)
		}
	}
}

func canonicalTransport(network string) string {
	switch network {
	case "raw":
		return "tcp"
	case "websocket":
		return "ws"
	case "splithttp":
		return "xhttp"
	case "mkcp":
		return "kcp"
	default:
		return network
	}
}

func syntheticShare(scheme, user, host, port, name string, query url.Values) string {
	link := url.URL{
		Scheme:   scheme,
		User:     url.User(user),
		Host:     host + ":" + port,
		Fragment: name,
		RawQuery: query.Encode(),
	}
	return link.String()
}

func TestShareFacadeProtocolIdentity(t *testing.T) {
	const id = "e9bff11a-27a7-49c7-8c21-2303c304de78"
	cases := []struct {
		name        string
		scheme      string
		exported    string
		user        string
		credentials string
		host        string
		port        string
		query       url.Values
		outbound    string
	}{
		{
			name: "VLESS reality 東京 #1", scheme: "vless", exported: "vless", user: id, host: "vless.invalid", port: "443",
			query: url.Values{
				"encryption": {"none"}, "flow": {"xtls-rprx-vision"}, "type": {"raw"}, "security": {"reality"},
				"sni": {"reality.invalid"}, "fp": {"chrome"}, "pbk": {"MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE"},
				"sid": {"0123456789abcdef"}, "spx": {"/spider?one=1&two=2"}, "pqv": {"synthetic-verifier+/="},
			},
			outbound: `{"protocol":"vless","settings":{"address":"vless.invalid","port":443,"id":"` + id + `","encryption":"none","flow":"xtls-rprx-vision"},"streamSettings":{"network":"raw","security":"reality","realitySettings":{"serverName":"reality.invalid","fingerprint":"chrome","publicKey":"MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE","shortId":"0123456789abcdef","spiderX":"/spider?one=1&two=2","mldsa65Verify":"synthetic-verifier+/="}}}`,
		},
		{
			name: "VMess websocket TLS", scheme: "vmess", exported: "vmess", user: id, host: "vmess.invalid", port: "8443",
			query: url.Values{
				"encryption": {"auto"}, "type": {"ws"}, "security": {"tls"}, "host": {"cdn.invalid"}, "path": {"/web socket?ed=2048&token=a+b"},
				"sni": {"tls.invalid"}, "fp": {"firefox"}, "alpn": {"h2,http/1.1"}, "ech": {"synthetic-ech+/="},
				"pcs": {"synthetic-pin+/="}, "vcn": {"verify.invalid"},
			},
			outbound: `{"protocol":"vmess","settings":{"address":"vmess.invalid","port":8443,"id":"` + id + `","security":"auto"},"streamSettings":{"network":"ws","security":"tls","wsSettings":{"host":"cdn.invalid","path":"/web socket?ed=2048&token=a+b"},"tlsSettings":{"serverName":"tls.invalid","fingerprint":"firefox","alpn":["h2","http/1.1"],"echConfigList":"synthetic-ech+/=","pinnedPeerCertSha256":"synthetic-pin+/=","verifyPeerCertByName":"verify.invalid"}}}`,
		},
		{
			name: "Trojan gRPC multi", scheme: "trojan", exported: "trojan", user: "secret:@/=?&#", host: "trojan.invalid", port: "443",
			query: url.Values{
				"type": {"grpc"}, "security": {"tls"}, "sni": {"grpc-tls.invalid"}, "mode": {"multi"},
				"serviceName": {"service/name?one=1&two=2"}, "authority": {"grpc.invalid"},
			},
			outbound: `{"protocol":"trojan","settings":{"address":"trojan.invalid","port":443,"password":"secret:@/=?&#"},"streamSettings":{"network":"grpc","security":"tls","grpcSettings":{"serviceName":"service/name?one=1&two=2","authority":"grpc.invalid","multiMode":true},"tlsSettings":{"serverName":"grpc-tls.invalid"}}}`,
		},
		{
			name: "Shadowsocks colon password", scheme: "ss", exported: "ss", host: "ss.invalid", port: "8388",
			user: base64.RawStdEncoding.EncodeToString([]byte("aes-128-gcm:password:with:colons")), credentials: "aes-128-gcm:password:with:colons",
			outbound: `{"protocol":"shadowsocks","settings":{"address":"ss.invalid","port":8388,"method":"aes-128-gcm","password":"password:with:colons"}}`,
		},
		{
			name: "SOCKS authenticated", scheme: "socks", exported: "socks", host: "socks.invalid", port: "1080",
			user: base64.RawStdEncoding.EncodeToString([]byte("synthetic-user:p:a:ss")), credentials: "synthetic-user:p:a:ss",
			outbound: `{"protocol":"socks","settings":{"address":"socks.invalid","port":1080,"user":"synthetic-user","pass":"p:a:ss"}}`,
		},
		{
			name: "Hysteria2 salamander", scheme: "hy2", exported: "hysteria2", user: "auth:@/?#", host: "hy.invalid", port: "8443",
			query:    url.Values{"sni": {"hy-tls.invalid"}, "obfs": {"salamander"}, "obfs-password": {"obfs key+/="}},
			outbound: `{"protocol":"hysteria","settings":{"version":2,"address":"hy.invalid","port":8443},"streamSettings":{"network":"hysteria","security":"tls","hysteriaSettings":{"version":2,"auth":"auth:@/?#"},"tlsSettings":{"serverName":"hy-tls.invalid"},"finalmask":{"udp":[{"type":"salamander","settings":{"password":"obfs key+/="}}]}}}`,
		},
		{
			name: "VLESS xHTTP extras", scheme: "vless", exported: "vless", user: id, host: "xhttp.invalid", port: "443",
			query: url.Values{
				"encryption": {"none"}, "type": {"xhttp"}, "security": {"tls"}, "sni": {"xhttp-tls.invalid"},
				"host": {"xhttp-host.invalid"}, "path": {"/connect?one=1&two=2"}, "mode": {"auto"},
				"extra": {`{"headers":{"X-Synthetic":"one&two"},"noGRPCHeader":true,"xPaddingBytes":"100-200"}`},
				"fm":    {`{"udp":[{"type":"salamander","settings":{"password":"mask+/="}}]}`},
			},
			outbound: `{"protocol":"vless","settings":{"address":"xhttp.invalid","port":443,"id":"` + id + `","encryption":"none"},"streamSettings":{"network":"xhttp","security":"tls","xhttpSettings":{"host":"xhttp-host.invalid","path":"/connect?one=1&two=2","mode":"auto","extra":{"headers":{"X-Synthetic":"one&two"},"noGRPCHeader":true,"xPaddingBytes":"100-200"}},"tlsSettings":{"serverName":"xhttp-tls.invalid"},"finalmask":{"udp":[{"type":"salamander","settings":{"password":"mask+/="}}]}}}`,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			input := syntheticShare(tc.scheme, tc.user, tc.host, tc.port, tc.name, tc.query)
			config := importShare(t, input)
			expected := []byte(`{"outbounds":[` + tc.outbound + `]}`)
			requireJSONFields(t, config, expected)

			exported := xray.Share(string(config))
			if exported == "" || strings.Contains(exported, "\n") {
				t.Fatalf("Share must return one usable URI, got %q", exported)
			}
			link, err := url.Parse(exported)
			if err != nil {
				t.Fatal(err)
			}
			sameScheme := link.Scheme == tc.exported || (tc.exported == "hysteria2" && link.Scheme == "hy2")
			if !sameScheme || link.Hostname() != tc.host || link.Port() != tc.port || link.Fragment != tc.name {
				t.Fatalf("Share changed protocol/server/port/display name: %s", exported)
			}
			if tc.credentials != "" {
				credentials := strings.ReplaceAll(strings.ReplaceAll(link.User.Username(), "-", "+"), "_", "/")
				decoded, err := base64.RawStdEncoding.DecodeString(strings.TrimRight(credentials, "="))
				if err != nil || string(decoded) != tc.credentials {
					t.Fatalf("Share changed credentials: %q, %v", decoded, err)
				}
			} else if link.User.Username() != tc.user {
				t.Fatalf("Share changed user/password: got %q, want %q", link.User.Username(), tc.user)
			}
			for key, values := range tc.query {
				actual := link.Query().Get(key)
				if key == "extra" || key == "fm" {
					requireJSONFields(t, []byte(actual), []byte(values[0]))
				} else if key == "type" {
					if canonicalTransport(actual) != canonicalTransport(values[0]) {
						t.Fatalf("Share changed transport: got %q, want %q", actual, values[0])
					}
				} else if actual != values[0] {
					t.Fatalf("Share changed %s: got %q, want %q", key, actual, values[0])
				}
			}
			requireJSONFields(t, importShare(t, exported), expected)
		})
	}
}

func TestShareFacadeVMessQRCode(t *testing.T) {
	for _, port := range []string{`443`, `"443"`} {
		t.Run(port, func(t *testing.T) {
			qr := `{"v":"2","ps":"QR 東京","add":"qr.invalid","port":` + port + `,"id":"e9bff11a-27a7-49c7-8c21-2303c304de78","scy":"auto","net":"ws","host":"qr-host.invalid","path":"/qr?one=1&two=2","tls":"tls","sni":"qr-tls.invalid","alpn":"h2,http/1.1","fp":"chrome"}`
			config := importShare(t, "vmess://"+base64.RawStdEncoding.EncodeToString([]byte(qr)))
			expected := []byte(`{"outbounds":[{"protocol":"vmess","settings":{"address":"qr.invalid","port":443,"id":"e9bff11a-27a7-49c7-8c21-2303c304de78","security":"auto"},"streamSettings":{"network":"ws","security":"tls","wsSettings":{"host":"qr-host.invalid","path":"/qr?one=1&two=2"},"tlsSettings":{"serverName":"qr-tls.invalid","fingerprint":"chrome","alpn":["h2","http/1.1"]}}}]}`)
			requireJSONFields(t, config, expected)
			exported := xray.Share(string(config))
			link, err := url.Parse(exported)
			if err != nil || link.Scheme != "vmess" || link.Fragment != "QR 東京" {
				t.Fatalf("Share lost VMess QR identity: %q (%v)", exported, err)
			}
			requireJSONFields(t, importShare(t, exported), expected)
		})
	}
}

func TestShareFacadeSubscriptionEncodingsAndOrder(t *testing.T) {
	first := syntheticShare("vless", "e9bff11a-27a7-49c7-8c21-2303c304de78", "first.invalid", "443", "first 東京", url.Values{"encryption": {"none"}})
	second := syntheticShare("socks", base64.RawStdEncoding.EncodeToString([]byte("user:pass")), "second.invalid", "1080", "second #2", nil)
	plain := first + "\r\n" + second
	cases := map[string]string{
		"plain CRLF and whitespace": " \n" + plain + "\r\n\t ",
		"Base64 padded":             base64.StdEncoding.EncodeToString([]byte(plain)),
		"Base64 URL unpadded":       base64.RawURLEncoding.EncodeToString([]byte(plain)),
	}
	for name, text := range cases {
		t.Run(name, func(t *testing.T) {
			config := importShare(t, text)
			requireJSONFields(t, config, []byte(`{"outbounds":[{"protocol":"vless","settings":{"address":"first.invalid","port":443}},{"protocol":"socks","settings":{"address":"second.invalid","port":1080,"user":"user","pass":"pass"}}]}`))
			lines := strings.Split(xray.Share(string(config)), "\n")
			if len(lines) != 2 {
				t.Fatalf("Share lost subscription entries: %q", lines)
			}
			for i, name := range []string{"first 東京", "second #2"} {
				link, err := url.Parse(lines[i])
				if err != nil || link.Fragment != name {
					t.Fatalf("Share changed subscription order/name at %d: %q (%v)", i, lines[i], err)
				}
			}
		})
	}
}

func TestShareFacadeJSONInput(t *testing.T) {
	config := []byte(`{"log":{"loglevel":"none"},"outbounds":[{"tag":"synthetic-direct","protocol":"freedom","settings":{"domainStrategy":"AsIs"}}]}`)
	requireJSONFields(t, importShare(t, string(config)), config)
}

func TestShareFacadeRejectsMalformedInput(t *testing.T) {
	cases := map[string]string{
		"empty":                      "",
		"unsupported scheme":         "ftp://unsupported.invalid:21/file",
		"malformed JSON":             `{"outbounds":[`,
		"JSON without outbounds":     `{"inbounds":[]}`,
		"missing VLESS port":         "vless://synthetic-id@missing-port.invalid?encryption=none",
		"invalid Shadowsocks Base64": "ss://!bad!@ss.invalid:8388",
		"SOCKS credentials no colon": "socks://" + base64.StdEncoding.EncodeToString([]byte("not-a-user-password-pair")) + "@socks.invalid:1080",
		"invalid xHTTP extra":        syntheticShare("vless", "synthetic-id", "xhttp.invalid", "443", "bad extra", url.Values{"type": {"xhttp"}, "extra": {"{"}}),
		"invalid final mask":         syntheticShare("vless", "synthetic-id", "mask.invalid", "443", "bad mask", url.Values{"fm": {"{"}}),
	}
	for name, input := range cases {
		t.Run(name, func(t *testing.T) {
			response := decodeShareResponse(t, xray.Json(input))
			if response.Success || response.Error == "" {
				t.Fatalf("Json must report failed import with a diagnostic, got %+v", response)
			}
		})
	}
	for name, input := range map[string]string{
		"empty": "", "malformed JSON": `{"outbounds":[`, "no outbounds": `{"outbounds":[]}`,
		"malformed supported settings": `{"outbounds":[{"protocol":"vless","settings":"not an object"}]}`,
	} {
		t.Run("export "+name, func(t *testing.T) {
			if exported := xray.Share(input); exported != "" {
				t.Fatalf("Share exported malformed configuration: %q", exported)
			}
		})
	}
}
