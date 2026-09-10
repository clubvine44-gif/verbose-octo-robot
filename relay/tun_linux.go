//go:build linux

package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const (
	tunSetIFF = 0x400454ca
	iffTun    = 0x0001
	iffNoPI   = 0x1000
	ifNameSz  = 16
)

type ifreq struct {
	Name  [ifNameSz]byte
	Flags uint16
	Pad   [22]byte
}

type tunDevice struct { f *os.File; name string }

func openTUN(name string) (*tunDevice, error) {
	f, err := os.OpenFile("/dev/net/tun", os.O_RDWR, 0)
	if err != nil { return nil, fmt.Errorf("open /dev/net/tun: %w", err) }
	var req ifreq
	copy(req.Name[:], name)
	req.Flags = iffTun | iffNoPI
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), uintptr(tunSetIFF), uintptr(unsafe.Pointer(&req))); errno != 0 {
		f.Close()
		return nil, fmt.Errorf("TUNSETIFF: %w", errno)
	}
	actual := string(req.Name[:])
	for i, c := range req.Name { if c == 0 { actual = string(req.Name[:i]); break } }
	return &tunDevice{f: f, name: actual}, nil
}

func (t *tunDevice) Read(p []byte) (int, error) { return t.f.Read(p) }
func (t *tunDevice) Write(p []byte) (int, error) { return t.f.Write(p) }
func (t *tunDevice) Close() error { return t.f.Close() }
