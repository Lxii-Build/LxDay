package main

import "testing"

func TestPrivateMediaMountPresentRecognizesExactMountPoint(t *testing.T) {
	mountInfo := "36 25 0:32 / /app/uploads-private rw,relatime - ext4 /dev/vda rw\n" +
		"37 25 0:33 / /app/uploads rw,relatime - ext4 /dev/vdb rw\n"

	mounted, err := privateMediaMountPresent("/app/uploads-private", mountInfo)
	if err != nil {
		t.Fatal(err)
	}
	if !mounted {
		t.Fatal("private media mount was not recognized")
	}
}

func TestPrivateMediaMountPresentDoesNotTreatParentMountAsPrivateMount(t *testing.T) {
	mountInfo := "36 25 0:32 / /app rw,relatime - ext4 /dev/vda rw\n"

	mounted, err := privateMediaMountPresent("/app/uploads-private", mountInfo)
	if err != nil {
		t.Fatal(err)
	}
	if mounted {
		t.Fatal("parent mount must not be accepted as a private-media volume")
	}
}

func TestUnescapeMountInfoPath(t *testing.T) {
	if got, want := unescapeMountInfoPath(`/app/private\040photos`), "/app/private photos"; got != want {
		t.Fatalf("unescape=%q want %q", got, want)
	}
}
