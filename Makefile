# ── Jump-host integration test ─────────────────────────────────────────────
# Starts two Alpine SSH containers, runs the JumpProxy end-to-end test,
# then tears everything down.
#
#   make test-jump          run the full test
#   make test-jump-clean    tear down containers and network only

JSCH      := $(shell find $(HOME)/.gradle/caches -name "jsch-0.2.17.jar" 2>/dev/null | head -1)
TEST_DIR  := /tmp/ssh-jump-test
KEY       := $(TEST_DIR)/test_key
NETWORK   := ssh-test-net
JUMP_PORT := 2222
USER      := tunnel

.PHONY: test-jump test-jump-clean

test-jump: test-jump-clean
	@echo "==> Generating test key"
	@mkdir -p $(TEST_DIR)
	@ssh-keygen -t ed25519 -f $(KEY) -N "" -C "jumptest" -q -f $(KEY) 2>/dev/null || true
	@echo "==> Building SSH image"
	@PUBKEY=$$(cat $(KEY).pub); \
	printf 'FROM alpine:3.19\nRUN apk add --no-cache openssh && ssh-keygen -A\nRUN adduser -D -s /bin/sh $(USER) && sed -i "s|^$(USER):!:|$(USER):*:|" /etc/shadow && mkdir -p /home/$(USER)/.ssh && chmod 700 /home/$(USER)/.ssh && echo "'"$$PUBKEY"'" > /home/$(USER)/.ssh/authorized_keys && chmod 600 /home/$(USER)/.ssh/authorized_keys && chown -R $(USER):$(USER) /home/$(USER)/.ssh\nRUN sed -i "s/^AllowTcpForwarding.*/AllowTcpForwarding yes/" /etc/ssh/sshd_config && echo "PubkeyAuthentication yes" >> /etc/ssh/sshd_config && echo "PasswordAuthentication no" >> /etc/ssh/sshd_config\nEXPOSE 22\nCMD ["/usr/sbin/sshd", "-D", "-e"]\n' \
	> $(TEST_DIR)/Dockerfile.sshd
	@docker build -t tunnel-light-sshd -f $(TEST_DIR)/Dockerfile.sshd $(TEST_DIR)/ -q
	@echo "==> Starting containers"
	@docker network create $(NETWORK) 2>/dev/null || true
	@docker run -d --name ssh-jump   --network $(NETWORK) -p $(JUMP_PORT):22 tunnel-light-sshd
	@docker run -d --name ssh-target --network $(NETWORK) tunnel-light-sshd
	@sleep 2
	@TARGET_IP=$$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' ssh-target); \
	echo "==> Jump: 127.0.0.1:$(JUMP_PORT)  Target: $$TARGET_IP:22"; \
	echo "==> Compiling test"; \
	javac -cp $(JSCH) $(TEST_DIR)/JumpTest.java -d $(TEST_DIR)/ 2>/dev/null || true; \
	echo "==> Running JumpProxy test"; \
	java -cp "$(TEST_DIR):$(JSCH)" \
	    -DjumpTestKey=$(KEY) \
	    -DjumpTestJumpHost=127.0.0.1 \
	    -DjumpTestJumpPort=$(JUMP_PORT) \
	    -DjumpTestTargetIp=$$TARGET_IP \
	    -DjumpTestTargetPort=22 \
	    -DjumpTestUser=$(USER) \
	    JumpTest; \
	$(MAKE) test-jump-clean

test-jump-clean:
	@docker rm -f ssh-jump ssh-target 2>/dev/null || true
	@docker network rm $(NETWORK) 2>/dev/null || true
