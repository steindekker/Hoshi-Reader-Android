# hoshi-bridge build. `make release` produces the static binary for the Deck and the PC.
VERSION ?= $(shell git describe --tags --always --dirty 2>/dev/null || echo 0.2.0)
LDFLAGS := -s -w -X main.version=$(VERSION)
DIST    := dist
PKG     := hoshi-bridge-$(VERSION)-linux-amd64
# For `make deploy`: ssh destination of the Deck, e.g. deck@steamdeck (Tailscale MagicDNS name).
DECK    ?= deck@steamdeck

.PHONY: all build test vet race release deploy install-pc clean

all: vet test build

build:
	go build -o hoshi-bridge ./cmd/hoshi-bridge

test:
	go test ./...

race:
	go test -race ./...

vet:
	go vet ./...

# Static linux/amd64 binary (no cgo, runs on SteamOS and Arch as-is) plus a
# tarball containing everything install.sh needs.
release:
	mkdir -p $(DIST)/$(PKG)/deploy $(DIST)/$(PKG)/lunahook
	CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags '$(LDFLAGS)' -o $(DIST)/hoshi-bridge ./cmd/hoshi-bridge
	CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags '$(LDFLAGS)' -o $(DIST)/$(PKG)/lunahook/vnhook.exe ./cmd/vnhook
	CGO_ENABLED=0 GOOS=windows GOARCH=386 go build -trimpath -ldflags '$(LDFLAGS)' -o $(DIST)/$(PKG)/lunahook/vnhook32.exe ./cmd/vnhook
	cp $(DIST)/hoshi-bridge install.sh README.md PROTOCOL.md $(DIST)/$(PKG)/
	cp deploy/hoshi-bridge.service deploy/hoshi-bridge-pc.service $(DIST)/$(PKG)/deploy/
	tar -C $(DIST) -czf $(DIST)/$(PKG).tar.gz $(PKG)
	@echo "built $(DIST)/hoshi-bridge and $(DIST)/$(PKG).tar.gz"

# Copy the release to the Deck over ssh (e.g. via Tailscale) and install it there.
deploy: release
	scp $(DIST)/$(PKG).tar.gz $(DECK):/tmp/
	ssh $(DECK) 'cd /tmp && tar xzf $(PKG).tar.gz && ./$(PKG)/install.sh'

# Install on this machine (the Linux PC running mpv).
install-pc: release
	./$(DIST)/$(PKG)/install.sh --pc

clean:
	rm -rf $(DIST) hoshi-bridge
