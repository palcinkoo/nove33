# Nove v3.2.0-ext — full build & deploy
SHELL := /bin/bash
ROOT := $(shell pwd)
NOVEMAIN := $(ROOT)/../Nove-main

.PHONY: all help install server-install dashboard-install \
        server-dev dashboard-dev server-build dashboard-build \
        android-debug android-release android-emu firebase-emu \
        docker-up docker-down docker-logs clean test

all: help

help:
	@echo "Nove v3.2.0-ext targets:"
	@echo "  make install           - install server + dashboard deps"
	@echo "  make server-dev        - run server (foreground)"
	@echo "  make dashboard-dev     - run Vite dev server"
	@echo "  make server-build      - production server (no docker)"
	@echo "  make dashboard-build   - build dashboard bundle"
	@echo "  make android-debug     - build debug APK"
	@echo "  make android-emu       - boot emulator and install debug APK"
	@echo "  make firebase-emu      - run local Firebase RTDB emulator"
	@echo "  make docker-up         - build & run docker-compose"
	@echo "  make docker-down       - stop docker-compose"
	@echo "  make test              - syntax check all JS/TS"

install: server-install dashboard-install
	@echo "deps installed"

server-install:
	cd $(NOVEMAIN)/server && npm install

dashboard-install:
	cd $(NOVEMAIN)/dashboard && npm install

server-dev:
	cd $(NOVEMAIN)/server && node index-extended.js

dashboard-dev:
	cd $(NOVEMAIN)/dashboard && npm run dev

server-build:
	cd $(NOVEMAIN)/server && npm install --omit=dev && NODE_ENV=production node index-extended.js

dashboard-build:
	cd $(NOVEMAIN)/dashboard && npm run build

android-debug:
	cd $(NOVEMAIN)/android && ./gradlew assembleDebug

android-release:
	cd $(NOVEMAIN)/android && ./gradlew assembleRelease

android-emu:
	$(NOVEMAIN)/scripts/emulator-test.sh

firebase-emu:
	$(ROOT)/tools/dev-firebase-emulator.sh

docker-up:
	docker compose -f $(ROOT)/deploy/docker-compose.yml --env-file $(ROOT)/deploy/.env up -d --build

docker-down:
	docker compose -f $(ROOT)/deploy/docker-compose.yml down

docker-logs:
	docker compose -f $(ROOT)/deploy/docker-compose.yml logs -f --tail=200

test:
	@node --check $(ROOT)/server/index-extended.js
	@node --check $(ROOT)/server/mount.js
	@node --check $(ROOT)/server/routes/extended.js
	@node --check $(ROOT)/server/routes/files.js
	@node --check $(ROOT)/server/lib/firebase.js
	@node --check $(ROOT)/server/lib/middleware.js
	@echo "syntax OK"

clean:
	rm -rf $(NOVEMAIN)/dashboard/dist $(NOVEMAIN)/server/node_modules $(NOVEMAIN)/dashboard/node_modules
