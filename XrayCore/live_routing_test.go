package XrayCore_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"testing"
	"time"

	xray "XrayCore"

	"golang.org/x/net/proxy"
)

func newLiveRoutingEcho(t *testing.T, marker byte) string {
	t.Helper()
	listener := reserveFacadeAddress(t)
	var lock sync.Mutex
	connections := make(map[net.Conn]bool)
	var workers sync.WaitGroup
	accepted := make(chan struct{})
	workers.Add(1)
	go func() {
		defer workers.Done()
		defer close(accepted)
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			lock.Lock()
			connections[conn] = true
			lock.Unlock()
			workers.Add(1)
			go func() {
				defer workers.Done()
				defer conn.Close()
				defer func() {
					lock.Lock()
					delete(connections, conn)
					lock.Unlock()
				}()
				if _, err := conn.Write([]byte{marker}); err == nil {
					io.Copy(conn, conn)
				}
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		// Finish the accept loop before waiting/closing the worker set.
		<-accepted
		lock.Lock()
		for conn := range connections {
			conn.Close()
		}
		lock.Unlock()
		workers.Wait()
	})
	return listener.Addr().String()
}

func liveRoutingConfig(port int, first, selected, memberA, memberB, strategy string, uidAccounts ...string) string {
	accounts := []any{map[string]any{"user": "synthetic-default", "pass": "synthetic-password"}}
	for _, uid := range uidAccounts {
		accounts = append(accounts, map[string]any{"user": uid, "pass": "synthetic-password"})
	}
	freedom := func(tag, redirect string) any {
		return map[string]any{"tag": tag, "protocol": "freedom", "settings": map[string]any{"redirect": redirect}}
	}
	group := map[string]any{"type": "field", "domain": []string{"full:group.invalid"}, "balancerTag": "group"}
	if strategy == "select" {
		delete(group, "balancerTag")
		group["outboundTag"] = "member.b"
	}
	routing := map[string]any{
		"domainStrategy": "AsIs",
		"rules": []any{
			map[string]any{"type": "field", "user": []string{"yaxc.uid.10001"}, "domain": []string{"full:uid.invalid"}, "outboundTag": "member.a"},
			map[string]any{"type": "field", "domain": []string{"full:route.invalid"}, "outboundTag": "selected"},
			group,
		},
	}
	if strategy != "select" {
		routing["balancers"] = []any{map[string]any{"tag": "group", "selector": []string{"member."}, "strategy": map[string]any{"type": strategy}}}
	}
	config := map[string]any{
		"log":       map[string]any{"loglevel": "none"},
		"inbounds":  []any{map[string]any{"tag": "socks", "listen": "127.0.0.1", "port": port, "protocol": "socks", "settings": map[string]any{"auth": "password", "accounts": accounts, "udp": true}}},
		"outbounds": []any{freedom("default", first), freedom("selected", selected), freedom("member.a", memberA), freedom("member.b", memberB)},
		"routing":   routing,
	}
	encoded, err := json.Marshal(config)
	if err != nil {
		panic(err)
	}
	return string(encoded)
}

func dialLiveRoutingSOCKS(address, username, destination string) (net.Conn, byte, error) {
	dialer, err := proxy.SOCKS5("tcp", address, &proxy.Auth{User: username, Password: "synthetic-password"}, &net.Dialer{Timeout: 2 * time.Second})
	if err != nil {
		return nil, 0, err
	}
	conn, err := dialer.Dial("tcp", destination+":80")
	if err != nil {
		return nil, 0, err
	}
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	var marker [1]byte
	if _, err := io.ReadFull(conn, marker[:]); err != nil {
		conn.Close()
		return nil, 0, err
	}
	return conn, marker[0], nil
}

func requireLiveRoutingEcho(t *testing.T, conn net.Conn, label string) {
	t.Helper()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	payload := append([]byte(label+"\x00\xff\n"), bytes.Repeat([]byte("live exact payload 東京"), 512)...)
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("%s: write failed: %v", label, err)
	}
	response := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, response); err != nil {
		t.Fatalf("%s: read failed: %v", label, err)
	}
	if !bytes.Equal(payload, response) {
		t.Fatalf("%s: response differs from payload", label)
	}
}

func requireLiveRoutingTarget(t *testing.T, address, username, destination string, want byte) {
	t.Helper()
	conn, marker, err := dialLiveRoutingSOCKS(address, username, destination)
	if err != nil {
		t.Fatalf("%s: SOCKS traffic failed: %v", destination, err)
	}
	defer conn.Close()
	if marker != want {
		t.Fatalf("%s: reached target %q, want %q", destination, marker, want)
	}
	requireLiveRoutingEcho(t, conn, destination)
}

func TestFacadeApplyRoutingPreservesRealSOCKSStreams(t *testing.T) {
	dir := isolateFacadeCore(t)
	targetA := newLiveRoutingEcho(t, 'A')
	targetB := newLiveRoutingEcho(t, 'B')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	initial := liveRoutingConfig(port, targetA, targetA, targetA, targetB, "random")
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, initial)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	held, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "route.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("initial retained stream failed: marker=%q error=%v", marker, err)
	}
	defer held.Close()
	requireLiveRoutingEcho(t, held, "before apply")
	requireLiveRoutingTarget(t, address, "synthetic-default", "unmatched.invalid", 'A')
	candidate := liveRoutingConfig(port, targetB, targetB, targetA, targetB, "roundRobin", "yaxc.uid.10001")
	if diagnostic := xray.ApplyRouting(candidate); diagnostic != "" {
		t.Fatalf("valid live apply rejected: %s", diagnostic)
	}
	requireLiveRoutingTarget(t, address, "synthetic-default", "route.invalid", 'B')
	requireLiveRoutingTarget(t, address, "synthetic-default", "unmatched.invalid", 'B')
	requireLiveRoutingTarget(t, address, "synthetic-default", "uid.invalid", 'B')
	requireLiveRoutingTarget(t, address, "yaxc.uid.10001", "uid.invalid", 'A')
	requireLiveRoutingTarget(t, address, "yaxc.uid.10001", "unmatched.invalid", 'B')
	requireLiveRoutingEcho(t, held, "after changed outbound and accounts")
	var previous byte
	for i := range 6 {
		conn, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "group.invalid")
		if err != nil {
			t.Fatal(err)
		}
		requireLiveRoutingEcho(t, conn, "round robin")
		conn.Close()
		if marker != 'A' && marker != 'B' || i > 0 && marker == previous {
			t.Fatalf("round robin did not alternate live group members: previous=%q current=%q", previous, marker)
		}
		previous = marker
	}
	for _, strategy := range []string{"random", "roundRobin", "select", "random", "select"} {
		candidate = liveRoutingConfig(port, targetB, targetB, targetA, targetB, strategy, "yaxc.uid.10001")
		if diagnostic := xray.ApplyRouting(candidate); diagnostic != "" {
			t.Fatalf("strategy %s rejected: %s", strategy, diagnostic)
		}
		if strategy == "select" {
			requireLiveRoutingTarget(t, address, "synthetic-default", "group.invalid", 'B')
		} else {
			conn, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "group.invalid")
			if err != nil {
				t.Fatal(err)
			}
			if marker != 'A' && marker != 'B' {
				t.Fatalf("unexpected live group member: %q", marker)
			}
			requireLiveRoutingEcho(t, conn, strategy)
			conn.Close()
		}
		requireLiveRoutingEcho(t, held, strategy+" retained old stream")
	}
	if diagnostic := xray.ApplyRouting(candidate); diagnostic != "" {
		t.Fatalf("identical repeated apply rejected: %s", diagnostic)
	}
	// This error is discovered by ReloadRules after changed handlers were
	// created and added. Neither those handlers nor candidate accounts commit.
	invalid := liveRoutingConfig(port, targetA, targetA, targetA, targetB, "select", "yaxc.uid.10001", "yaxc.uid.10002")
	invalid = strings.Replace(invalid, `"outboundTag":"selected"`, `"outboundTag":"selected","ruleTag":"duplicate"`, 1)
	invalid = strings.Replace(invalid, `"outboundTag":"member.a"`, `"outboundTag":"member.a","ruleTag":"duplicate"`, 1)
	for _, rejected := range []string{
		invalid,
		strings.Replace(candidate, `"loglevel":"none"`, `"loglevel":"warning"`, 1),
		strings.Replace(candidate, `"auth":"password"`, `"auth":"noauth"`, 1),
		strings.Replace(candidate, `synthetic-password`, `changed-default-password`, 1),
		strings.Replace(candidate, `"domainStrategy":"AsIs"`, `"domainStrategy":"IPIfNonMatch"`, 1),
		`{"outbounds":[`,
	} {
		if diagnostic := xray.ApplyRouting(rejected); diagnostic == "" {
			t.Fatal("invalid live update accepted")
		}
		requireLiveRoutingTarget(t, address, "synthetic-default", "route.invalid", 'B')
		requireLiveRoutingTarget(t, address, "synthetic-default", "unmatched.invalid", 'B')
		requireLiveRoutingEcho(t, held, "after rejected update")
	}
	if conn, _, err := dialLiveRoutingSOCKS(address, "yaxc.uid.10002", "uid.invalid"); err == nil {
		conn.Close()
		t.Fatal("failed apply published candidate SOCKS accounts")
	}
	// Removing dynamic credentials affects future authentication only.
	uidStream, marker, err := dialLiveRoutingSOCKS(address, "yaxc.uid.10001", "uid.invalid")
	if err != nil || marker != 'A' {
		t.Fatalf("UID stream failed: marker=%q error=%v", marker, err)
	}
	defer uidStream.Close()
	withoutUID := liveRoutingConfig(port, targetB, targetB, targetA, targetB, "select")
	if diagnostic := xray.ApplyRouting(withoutUID); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	if conn, _, err := dialLiveRoutingSOCKS(address, "yaxc.uid.10001", "uid.invalid"); err == nil {
		conn.Close()
		t.Fatal("removed UID can still authenticate")
	}
	requireLiveRoutingEcho(t, uidStream, "removed account retained stream")
	requireLiveRoutingEcho(t, held, "all live updates retained original stream")
	if diagnostic := xray.Stop(); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	requireFacadeListenerReleased(t, address)
	if diagnostic := xray.ApplyRouting(candidate); diagnostic == "" {
		t.Fatal("ApplyRouting accepted a stopped core")
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, initial)); diagnostic != "" {
		t.Fatalf("restart after live updates failed: %s", diagnostic)
	}
	requireLiveRoutingTarget(t, address, "synthetic-default", "unmatched.invalid", 'A')
}

func TestFacadeApplyRoutingConcurrentSOCKSAuthentication(t *testing.T) {
	dir := isolateFacadeCore(t)
	target := newLiveRoutingEcho(t, 'A')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	candidate := liveRoutingConfig(port, target, target, target, target, "random", "yaxc.uid.10001")
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, candidate)); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	errors := make(chan error, 1)
	go func() {
		for range 32 {
			conn, marker, err := dialLiveRoutingSOCKS(address, "synthetic-default", "group.invalid")
			if err != nil {
				errors <- err
				return
			}
			conn.Close()
			if marker != 'A' {
				errors <- fmt.Errorf("unexpected marker %q", marker)
				return
			}
		}
		errors <- nil
	}()
	for i := range 32 {
		strategy := "random"
		if i%2 == 0 {
			strategy = "roundRobin"
		}
		var accounts []string
		if i%3 != 0 {
			accounts = []string{"yaxc.uid.10001"}
		}
		if diagnostic := xray.ApplyRouting(liveRoutingConfig(port, target, target, target, target, strategy, accounts...)); diagnostic != "" {
			t.Fatalf("concurrent live update failed: %s", diagnostic)
		}
	}
	if err := <-errors; err != nil {
		t.Fatalf("authentication/route lookup during live apply failed: %v", err)
	}
}

func TestUidIdentityRetainsOnlyTheExplicitDefaultAccountsBaseRules(t *testing.T) {
	dir := isolateFacadeCore(t)
	targetA := newLiveRoutingEcho(t, 'A')
	targetB := newLiveRoutingEcho(t, 'B')
	reservation := reserveFacadeAddress(t)
	address := reservation.Addr().String()
	port := reservation.Addr().(*net.TCPAddr).Port
	reservation.Close()
	makeConfig := func(users ...string) string {
		var config map[string]any
		if err := json.Unmarshal([]byte(liveRoutingConfig(port, targetB, targetB, targetA, targetB, "select", users...)), &config); err != nil {
			t.Fatal(err)
		}
		config["_yaxcDefaultSocksUser"] = "synthetic-default"
		inbound := config["inbounds"].([]any)[0].(map[string]any)["settings"].(map[string]any)
		inbound["accounts"] = append(inbound["accounts"].([]any), map[string]any{"user": "other-client", "pass": "synthetic-password"})
		routing := config["routing"].(map[string]any)
		routing["rules"] = append([]any{
			map[string]any{"user": []string{"synthetic-default"}, "domain": []string{"full:legacy-literal.invalid"}, "outboundTag": "member.a"},
			map[string]any{"user": []string{"regexp:^synthetic-default$"}, "domain": []string{"full:legacy-regexp.invalid"}, "outboundTag": "member.a"},
			map[string]any{"user": []string{"other-client"}, "domain": []string{"full:other-client.invalid"}, "outboundTag": "member.a"},
		}, routing["rules"].([]any)...)
		encoded, err := json.Marshal(config)
		if err != nil {
			t.Fatal(err)
		}
		return string(encoded)
	}
	if diagnostic := xray.Start(dir, writeFacadeConfig(t, makeConfig("yaxc.uid.10001"))); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	requireLiveRoutingTarget(t, address, "yaxc.uid.10001", "legacy-literal.invalid", 'A')
	requireLiveRoutingTarget(t, address, "yaxc.uid.10001", "legacy-regexp.invalid", 'A')
	requireLiveRoutingTarget(t, address, "other-client", "other-client.invalid", 'A')
	requireLiveRoutingTarget(t, address, "yaxc.uid.10001", "other-client.invalid", 'B')
	if diagnostic := xray.ApplyRouting(makeConfig("yaxc.uid.10001", "yaxc.uid.10002")); diagnostic != "" {
		t.Fatal(diagnostic)
	}
	requireLiveRoutingTarget(t, address, "yaxc.uid.10002", "legacy-literal.invalid", 'A')
	requireLiveRoutingTarget(t, address, "yaxc.uid.10002", "legacy-regexp.invalid", 'A')
	requireLiveRoutingTarget(t, address, "yaxc.uid.10002", "other-client.invalid", 'B')
}
