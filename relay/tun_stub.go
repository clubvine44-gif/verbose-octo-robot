//go:build !linux

package main

import (
	"fmt"
	"time"
)

type tunDevice struct{}

func openTUN(name string) (*tunDevice, error) {
	return nil, fmt.Errorf("server TUN support is only implemented on Linux")
}
func (t *tunDevice) Read(p []byte) (int, error) { return 0, fmt.Errorf("TUN unavailable") }
func (t *tunDevice) Write(p []byte) (int, error) { return 0, fmt.Errorf("TUN unavailable") }
func (t *tunDevice) Close() error { return nil }
func (t *tunDevice) SetReadDeadline(deadline time.Time) error { return nil }
