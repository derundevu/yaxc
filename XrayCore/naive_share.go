package XrayCore

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"reflect"
	"strconv"
	"strings"

	"github.com/xtls/libxray/share"
	"github.com/xtls/xray-core/infra/conf"
)

func isNaiveLink(text string) bool {
	return strings.HasPrefix(strings.ToLower(text), "naive+https:") || strings.HasPrefix(strings.ToLower(text), "naive+quic:")
}

// Keep the retained wrapper's other formats and response contract. Naive is
// converted here because its Chromium transport is a first-party capability.
func convertShareLinksToXrayJSON(text string) (*conf.Config, error) {
	plain := strings.TrimSpace(text)
	if strings.HasPrefix(plain, "{") {
		config, err := share.ConvertShareLinksToXrayJson(text)
		if err != nil {
			return nil, err
		}
		var document struct {
			Outbounds []json.RawMessage `json:"outbounds"`
		}
		if err := json.Unmarshal([]byte(text), &document); err != nil {
			return nil, err
		}
		for index, outbound := range config.OutboundConfigs {
			if strings.EqualFold(outbound.Protocol, "naive") {
				var strict conf.OutboundDetourConfig
				var settings conf.NaiveClientConfig
				if decodeStrictJSON(document.Outbounds[index], &strict) != nil || strict.Settings == nil || decodeStrictJSON(*strict.Settings, &settings) != nil {
					return nil, fmt.Errorf("naive: unknown or invalid native options")
				}
				if _, err := strict.Build(); err != nil {
					return nil, fmt.Errorf("naive: invalid native outbound")
				}
			}
		}
		return config, nil
	}
	containsNaive := func(value string) bool {
		for _, line := range strings.Split(value, "\n") {
			if isNaiveLink(strings.TrimSpace(line)) {
				return true
			}
		}
		return false
	}
	if !containsNaive(plain) {
		for _, encoding := range []*base64.Encoding{base64.StdEncoding, base64.RawStdEncoding, base64.URLEncoding, base64.RawURLEncoding} {
			decoded, err := encoding.DecodeString(plain)
			if err == nil && containsNaive(string(decoded)) {
				plain = string(decoded)
				break
			}
		}
	}
	if !containsNaive(plain) {
		return share.ConvertShareLinksToXrayJson(text)
	}
	config := &conf.Config{}
	for index, line := range strings.Split(plain, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if isNaiveLink(line) {
			outbound, err := parseNaiveShare(line)
			if err != nil {
				return nil, fmt.Errorf("share entry %d: %w", index+1, err)
			}
			config.OutboundConfigs = append(config.OutboundConfigs, *outbound)
			continue
		}
		// Do not let the old parser silently discard an unsupported record from
		// a mixed Naive subscription. This facade has no skipped-entry channel.
		scheme, _, _ := strings.Cut(line, "://")
		switch scheme {
		case "vless", "vmess", "socks", "ss", "trojan", "hysteria2", "hy2":
		default:
			return nil, fmt.Errorf("share entry %d: unsupported or invalid record", index+1)
		}
		parsed, err := share.ConvertShareLinksToXrayJson(line)
		if err != nil || parsed == nil || len(parsed.OutboundConfigs) == 0 {
			return nil, fmt.Errorf("share entry %d: unsupported or invalid record", index+1)
		}
		config.OutboundConfigs = append(config.OutboundConfigs, parsed.OutboundConfigs...)
	}
	return config, nil
}

func parseNaiveShare(text string) (*conf.OutboundDetourConfig, error) {
	link, err := url.Parse(text)
	if err != nil || link.Opaque != "" || link.Hostname() == "" || (link.Path != "" && link.Path != "/") || link.ForceQuery {
		return nil, fmt.Errorf("naive: invalid proxy URI")
	}
	quic := strings.EqualFold(link.Scheme, "naive+quic")
	port := uint64(443)
	if link.Port() != "" {
		port, err = strconv.ParseUint(link.Port(), 10, 16)
		if err != nil || port == 0 {
			return nil, fmt.Errorf("naive: invalid port")
		}
	} else if strings.HasSuffix(link.Host, ":") {
		return nil, fmt.Errorf("naive: invalid port")
	}
	query, err := url.ParseQuery(link.RawQuery)
	if err != nil {
		return nil, fmt.Errorf("naive: invalid query encoding")
	}
	for key, values := range query {
		if (key != "sni" && key != "padding" && key != "tfo") || len(values) != 1 {
			return nil, fmt.Errorf("naive: unsupported or duplicate URI option")
		}
	}
	if values, present := query["padding"]; present {
		enabled, err := strconv.ParseBool(values[0])
		if err != nil || !enabled {
			return nil, fmt.Errorf("naive: Chromium padding cannot be disabled or customized")
		}
	}
	stream := &conf.StreamConfig{Security: "tls", TLSSettings: &conf.TLSConfig{ServerName: query.Get("sni")}}
	network := conf.TransportProtocol("raw")
	stream.Network = &network
	if values, present := query["tfo"]; present {
		enabled, err := strconv.ParseBool(values[0])
		if err != nil || (quic && enabled) {
			return nil, fmt.Errorf("naive: invalid TCP Fast Open option for this transport")
		}
		stream.SocketSettings = &conf.SocketConfig{TFO: enabled}
	}
	settings := conf.NaiveClientConfig{Port: uint16(port), QUIC: quic, Headers: map[string]string{}}
	settings.Address = &conf.Address{}
	if err := settings.Address.UnmarshalJSON([]byte(strconv.Quote(link.Hostname()))); err != nil {
		return nil, fmt.Errorf("naive: invalid server address")
	}
	if link.User != nil {
		settings.Username = link.User.Username()
		settings.Password, _ = link.User.Password()
	}
	raw, err := json.Marshal(settings)
	if err != nil {
		return nil, err
	}
	message := json.RawMessage(raw)
	outbound := &conf.OutboundDetourConfig{Protocol: "naive", Tag: link.Fragment, Settings: &message, StreamSetting: stream}
	if _, err := outbound.Build(); err != nil {
		return nil, fmt.Errorf("naive: invalid native address, credentials or TLS settings")
	}
	return outbound, nil
}

func decodeStrictJSON(data []byte, value any) error {
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	return decoder.Decode(value)
}

func convertXrayJSONToShareLinks(data []byte) (string, error) {
	var document struct {
		Outbounds []json.RawMessage `json:"outbounds"`
	}
	if err := json.Unmarshal(data, &document); err != nil {
		return "", err
	}
	hasNaive := false
	for _, raw := range document.Outbounds {
		var header struct {
			Protocol string `json:"protocol"`
		}
		if err := json.Unmarshal(raw, &header); err != nil {
			return "", err
		}
		if strings.EqualFold(header.Protocol, "naive") {
			hasNaive = true
		}
	}
	if !hasNaive {
		return share.ConvertXrayJsonToShareLinks(data)
	}
	var links []string
	for _, raw := range document.Outbounds {
		var header struct {
			Protocol string `json:"protocol"`
		}
		if err := json.Unmarshal(raw, &header); err != nil {
			return "", err
		}
		if strings.EqualFold(header.Protocol, "naive") {
			link, err := exportNaiveShare(raw)
			if err != nil {
				return "", err
			}
			links = append(links, link)
			continue
		}
		switch header.Protocol {
		case "shadowsocks", "vmess", "vless", "socks", "trojan", "hysteria":
			link, err := share.ConvertXrayJsonToShareLinks([]byte(`{"outbounds":[` + string(raw) + `]}`))
			if err != nil {
				return "", err
			}
			links = append(links, link)
		}
	}
	return strings.Join(links, "\n"), nil
}

func exportNaiveShare(raw []byte) (string, error) {
	var outbound conf.OutboundDetourConfig
	if err := decodeStrictJSON(raw, &outbound); err != nil {
		return "", fmt.Errorf("naive: unknown or invalid outbound options")
	}
	if outbound.Settings == nil || outbound.StreamSetting == nil {
		return "", fmt.Errorf("naive: settings and TLS transport are required")
	}
	var settings conf.NaiveClientConfig
	if err := decodeStrictJSON(*outbound.Settings, &settings); err != nil {
		return "", fmt.Errorf("naive: unknown or invalid protocol options")
	}
	unrepresented := settings
	unrepresented.Address, unrepresented.Port = nil, 0
	unrepresented.Username, unrepresented.Password = "", ""
	unrepresented.QUIC = false
	if len(unrepresented.Headers) == 0 {
		unrepresented.Headers = nil
	}
	if !reflect.DeepEqual(unrepresented, conf.NaiveClientConfig{}) {
		return "", fmt.Errorf("naive: protocol options cannot be represented in a share URI")
	}
	// Inspect the original typed fields, not just Build's normalized output:
	// otherwise ignored TLS overrides could turn into misleading working links.
	remaining := outbound
	remaining.Protocol, remaining.Tag = "", ""
	remaining.Settings, remaining.StreamSetting = nil, nil
	if !reflect.DeepEqual(remaining, conf.OutboundDetourConfig{}) {
		return "", fmt.Errorf("naive: outbound routing or sender options cannot be represented in a share URI")
	}
	stream := *outbound.StreamSetting
	if stream.Security != "tls" || (stream.Network != nil && *stream.Network != "raw" && *stream.Network != "tcp") {
		return "", fmt.Errorf("naive: share URIs require raw TLS transport")
	}
	query := url.Values{}
	if stream.TLSSettings != nil {
		tls := *stream.TLSSettings
		if tls.ServerName != "" {
			query.Set("sni", tls.ServerName)
		}
		tls.ServerName = ""
		if !reflect.DeepEqual(tls, conf.TLSConfig{}) {
			return "", fmt.Errorf("naive: TLS overrides cannot be represented in a share URI")
		}
	}
	if stream.SocketSettings != nil {
		socket := *stream.SocketSettings
		if socket.TFO != nil {
			enabled, ok := socket.TFO.(bool)
			if !ok || (settings.QUIC && enabled) {
				return "", fmt.Errorf("naive: TCP Fast Open option cannot be represented in this share URI")
			}
			query.Set("tfo", strconv.FormatBool(enabled))
		}
		socket.TFO = nil
		if !reflect.DeepEqual(socket, conf.SocketConfig{}) {
			return "", fmt.Errorf("naive: socket options cannot be represented in a share URI")
		}
	}
	stream.Network, stream.TLSSettings, stream.SocketSettings = nil, nil, nil
	stream.Security = ""
	if !reflect.DeepEqual(stream, conf.StreamConfig{}) {
		return "", fmt.Errorf("naive: transport options cannot be represented in a share URI")
	}
	if _, err := outbound.Build(); err != nil {
		return "", fmt.Errorf("naive: invalid native outbound")
	}
	host := settings.Address.String()
	if settings.Address.Family().IsIP() {
		host = settings.Address.IP().String()
	}
	link := url.URL{Scheme: "naive+https", Host: net.JoinHostPort(host, strconv.Itoa(int(settings.Port))), Fragment: outbound.Tag, RawQuery: query.Encode()}
	if settings.QUIC {
		link.Scheme = "naive+quic"
	}
	if settings.Username != "" || settings.Password != "" {
		link.User = url.UserPassword(settings.Username, settings.Password)
	}
	if link.Fragment == "" {
		link.Fragment = "naive"
	}
	return link.String(), nil
}
