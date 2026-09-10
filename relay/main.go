package main

import (
	"crypto/tls"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"
)

const (
	magic             uint32 = 0x4D594B31 // MYK1
	version                  = 1
	typeIP             byte  = 1
	typeClose          byte  = 2
	maxPayload               = 65535
	maxPacket                = 65535
	handshakeTimeout         = 10 * time.Second
	tunPollInterval          = 1 * time.Second
)

type frame struct {
	typ     byte
	payload []byte
}

type packetDevice interface {
	io.ReadWriter
	io.Closer
	SetReadDeadline(time.Time) error
}

func readFrame(r io.Reader) (frame, error) {
	var h [10]byte
	if _, err := io.ReadFull(r, h[:]); err != nil {
		return frame{}, err
	}
	if binary.BigEndian.Uint32(h[0:4]) != magic {
		return frame{}, fmt.Errorf("invalid MAYAK magic")
	}
	if h[4] != version {
		return frame{}, fmt.Errorf("unsupported MAYAK version: %d", h[4])
	}
	length := int(binary.BigEndian.Uint32(h[6:10]))
	if length > maxPayload {
		return frame{}, fmt.Errorf("invalid frame length: %d", length)
	}
	payload := make([]byte, length)
	if _, err := io.ReadFull(r, payload); err != nil {
		return frame{}, err
	}
	return frame{typ: h[5], payload: payload}, nil
}

func writeFrame(w io.Writer, typ byte, payload []byte) error {
	if len(payload) > maxPayload {
		return fmt.Errorf("payload too large: %d", len(payload))
	}
	var h [10]byte
	binary.BigEndian.PutUint32(h[0:4], magic)
	h[4] = version
	h[5] = typ
	binary.BigEndian.PutUint32(h[6:10], uint32(len(payload)))
	if _, err := w.Write(h[:]); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

func tunnelClient(conn net.Conn, tun packetDevice) {
	defer conn.Close()
	defer tun.SetReadDeadline(time.Now())

	var writeMu sync.Mutex
	done := make(chan struct{})
	var once sync.Once
	stop := func() { once.Do(func() { close(done) }) }

	go func() {
		buf := make([]byte, maxPacket)
		for {
			n, err := tun.Read(buf)
			if err != nil {
				if ne, ok := err.(net.Error); ok && ne.Timeout() {
					select {
					case <-done:
						return
					default:
						continue
					}
				}
				stop()
				return
			}
			if n == 0 || (buf[0]>>4) != 4 {
				continue
			}
			packet := append([]byte(nil), buf[:n]...)
			writeMu.Lock()
			err = writeFrame(conn, typeIP, packet)
			writeMu.Unlock()
			if err != nil {
				stop()
				return
			}
		}
	}()

	for {
		select {
		case <-done:
			return
		default:
		}
		f, err := readFrame(conn)
		if err != nil {
			if err != io.EOF && err != io.ErrUnexpectedEOF {
				log.Printf("client %s: %v", conn.RemoteAddr(), err)
			}
			stop()
			return
		}
		switch f.typ {
		case typeIP:
			if len(f.payload) == 0 || (f.payload[0]>>4) != 4 {
				log.Printf("client %s: rejected non-IPv4 packet", conn.RemoteAddr())
				stop()
				return
			}
			if _, err := tun.Write(f.payload); err != nil {
				log.Printf("client %s: TUN write: %v", conn.RemoteAddr(), err)
				stop()
				return
			}
		case typeClose:
			stop()
			return
		default:
			log.Printf("client %s: unsupported frame type %d", conn.RemoteAddr(), f.typ)
			stop()
			return
		}
	}
}

func main() {
	listenAddr := flag.String("listen", ":4433", "TLS listen address")
	certFile := flag.String("cert", "server.crt", "TLS certificate PEM")
	keyFile := flag.String("key", "server.key", "TLS private key PEM")
	tunName := flag.String("tun", "mayak0", "Linux TUN interface name")
	flag.Parse()

	tun, err := openTUN(*tunName)
	if err != nil {
		log.Fatalf("open TUN: %v", err)
	}
	defer tun.Close()
	log.Printf("MAYAK TUN ready: %s", tun.name)

	cert, err := tls.LoadX509KeyPair(*certFile, *keyFile)
	if err != nil {
		log.Fatalf("load TLS certificate: %v", err)
	}
	cfg := &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
		MaxVersion:   tls.VersionTLS13,
	}
	ln, err := tls.Listen("tcp", *listenAddr, cfg)
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	defer ln.Close()
	log.Printf("MAYAK relay listening on %s (single client)", *listenAddr)

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		tlsConn, ok := conn.(*tls.Conn)
		if !ok {
			conn.Close()
			continue
		}
		_ = tlsConn.SetDeadline(time.Now().Add(handshakeTimeout))
		if err := tlsConn.Handshake(); err != nil {
			log.Printf("TLS handshake from %s failed: %v", conn.RemoteAddr(), err)
			conn.Close()
			continue
		}
		_ = tlsConn.SetDeadline(time.Time{})
		log.Printf("client connected: %s", conn.RemoteAddr())
		tunnelClient(tlsConn, tun)
		log.Printf("client disconnected: %s", conn.RemoteAddr())
	}
}
