package XrayCore_test

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	xray "XrayCore"
)

const facadeValidationWorkerTimeout = 20 * time.Second

type facadeValidationResult struct {
	Version    int     `json:"version"`
	ProcessID  int     `json:"processId"`
	Diagnostic *string `json:"diagnostic"`
}

// TestFacadeValidationWorkerProcess is the test-binary entrypoint for real
// isolated validation. Invalid configurations are successful worker operations;
// only transport/protocol failures cause a failing child exit status.
func TestFacadeValidationWorkerProcess(t *testing.T) {
	if os.Getenv("YAXC_VALIDATION_WORKER") != "1" {
		return
	}
	dir := os.Getenv("YAXC_VALIDATION_ASSETS")
	config := os.Getenv("YAXC_VALIDATION_CONFIG")
	resultPath := os.Getenv("YAXC_VALIDATION_RESULT")
	if dir == "" || config == "" || resultPath == "" {
		t.Fatal("validation worker is missing its request paths")
	}
	diagnostic := xray.ValidateInWorker(dir, config)
	data, err := json.Marshal(facadeValidationResult{1, os.Getpid(), &diagnostic})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(resultPath, data, 0600); err != nil {
		t.Fatal(err)
	}
}

func runFacadeValidationWorker(ctx context.Context, dir, config string) (string, error) {
	resultDir, err := os.MkdirTemp("", "yaxc-validation-result-")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(resultDir)
	resultPath := filepath.Join(resultDir, "result.json")
	ctx, cancel := context.WithTimeout(ctx, facadeValidationWorkerTimeout)
	defer cancel()
	command := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestFacadeValidationWorkerProcess$", "-test.timeout=15s")
	command.WaitDelay = time.Second
	command.Env = append(os.Environ(),
		"YAXC_VALIDATION_WORKER=1",
		"YAXC_VALIDATION_ASSETS="+dir,
		"YAXC_VALIDATION_CONFIG="+config,
		"YAXC_VALIDATION_RESULT="+resultPath,
	)
	if output, err := command.CombinedOutput(); err != nil {
		return "", fmt.Errorf("validation worker failed (deadline: %v): %w\n%s", ctx.Err(), err, output)
	}
	data, err := os.ReadFile(resultPath)
	if err != nil {
		return "", fmt.Errorf("reading validation worker result: %w", err)
	}
	var result facadeValidationResult
	if err := json.Unmarshal(data, &result); err != nil {
		return "", fmt.Errorf("decoding validation worker result: %w", err)
	}
	if result.Version != 1 || result.ProcessID != command.Process.Pid || result.ProcessID == os.Getpid() || result.Diagnostic == nil {
		return "", fmt.Errorf("invalid validation worker result: %s", data)
	}
	return *result.Diagnostic, nil
}

func validateFacadeInWorker(t *testing.T, dir, config string) string {
	t.Helper()
	diagnostic, err := runFacadeValidationWorker(context.Background(), dir, config)
	if err != nil {
		t.Fatal(err)
	}
	return diagnostic
}
