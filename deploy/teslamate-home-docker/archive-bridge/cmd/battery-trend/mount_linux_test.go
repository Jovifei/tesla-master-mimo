//go:build linux

package main

import (
	"os"
	"strings"
	"testing"
)

func TestManagedModeRejectsWritableOriginalState(t *testing.T) {
	c := syntheticConfig(t, "https://synthetic.example")
	if requireReadOnlyOriginal(c, "/not-used/credentials.sock") == nil {
		t.Fatal("writable original state was accepted")
	}
}

func TestCurrentContainerMountInfoCanBeRead(t *testing.T) {
	f, err := os.Open("/proc/self/mountinfo")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	mounts, err := readMountBackings(f)
	if err != nil {
		t.Fatal("current container mount information rejected")
	}
	if _, _, err := backingFor("/proc/self/mountinfo", mounts); err != nil {
		t.Fatal(err)
	}
}

func TestSyntheticContainerMountIsolation(t *testing.T) {
	original := os.Getenv("MATELINK_SYNTHETIC_ORIGINAL")
	if original == "" {
		t.Skip("explicit synthetic mounts not configured")
	}
	c := config{OriginalStateFile: original, StateFile: "/trend/state.json"}
	err := requireReadOnlyOriginal(c, "/socket/credentials.sock")
	if os.Getenv("MATELINK_SYNTHETIC_EXPECT_ALIAS") == "1" {
		if err == nil {
			t.Fatal("writable alias of original state was accepted")
		}
		return
	}
	if err != nil {
		t.Fatalf("separate read-only fixture rejected: %v", err)
	}
	f, err := os.OpenFile(original, os.O_WRONLY, 0)
	if err == nil {
		f.Close()
		t.Fatal("original mount unexpectedly writable")
	}
	if err := os.Remove(original); err == nil {
		t.Fatal("original mount unexpectedly removable")
	}
}

func TestMountBackingDetectsReadOnlyAliasAndKeepsDistinctVolumes(t *testing.T) {
	mountinfo := "1 0 8:1 / / rw - ext4 root rw\n" +
		"2 1 8:1 /user/source /original ro - ext4 root rw\n" +
		"3 1 8:1 /user/source /trend-alias rw - ext4 root rw\n" +
		"4 1 8:1 /user/other\\040volume /trend rw - ext4 root rw\n" +
		"5 1 8:1 /user/socket /socket ro - ext4 root rw\n"
	mounts, err := readMountBackings(strings.NewReader(mountinfo))
	if err != nil {
		t.Fatal(err)
	}
	om, op, err := backingFor("/original/state.json", mounts)
	if err != nil {
		t.Fatal(err)
	}
	am, ap, err := backingFor("/trend-alias", mounts)
	if err != nil || om.device != am.device || !pathWithin(op, ap) {
		t.Fatal("original writable alias missed")
	}
	tm, tp, err := backingFor("/trend", mounts)
	if err != nil || om.device != tm.device || pathWithin(op, tp) || tp != "/user/other volume" {
		t.Fatal("independent volume or escaped path misread")
	}
	if _, err := readMountBackings(strings.NewReader(mountinfo + "6 1 9:2 /else /original ro - ext4 root rw\n")); err == nil {
		t.Fatal("stacked ambiguous mount accepted")
	}
}
