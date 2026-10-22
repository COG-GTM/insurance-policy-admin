.PHONY: compat compat-static compat-api compat-build test dev-env dev-images dev dev-down dev-clean local

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

test:
	cd pas-platform && ./mvnw -B verify

dev-env:
	cd pas-platform && if [ ! -f .env ]; then cp .env.example .env; fi
	cd pas-platform && ./dev/dev-jwt.sh keygen

dev-images:
	cd pas-platform && \
	BUILD_ARGS=""; \
	if [ -n "$(MVNW_REPOURL)" ]; then BUILD_ARGS="$$BUILD_ARGS --build-arg MVNW_REPOURL=$(MVNW_REPOURL)"; fi; \
	if [ -n "$(MAVEN_SETTINGS)" ] && [ -f "$(MAVEN_SETTINGS)" ]; then \
		BUILD_ARGS="$$BUILD_ARGS --secret id=maven_settings,src=$(MAVEN_SETTINGS)"; \
	fi; \
	docker build $$BUILD_ARGS -f pas-policy-service/Dockerfile -t pas-policy-service:local . && \
	docker build $$BUILD_ARGS -f pas-batch/Dockerfile -t pas-batch:local .

dev: dev-env dev-images
	cd pas-platform && docker compose up -d --wait
	@cd pas-platform && printf 'Policy readiness: http://localhost:%s/actuator/health/readiness\n' "$$(docker compose port pas-policy-service 8080 | sed 's/.*://')"
	@cd pas-platform && printf 'Batch readiness:  http://localhost:%s/actuator/health/readiness\n' "$$(docker compose port pas-batch 8081 | sed 's/.*://')"

dev-down:
	cd pas-platform && docker compose down

dev-clean:
	cd pas-platform && docker compose down -v

local:
	$(MAKE) test
	$(MAKE) dev
	@cd pas-platform && \
	POLICY_PORT="$$(docker compose port pas-policy-service 8080 | sed 's/.*://')" && \
	BATCH_PORT="$$(docker compose port pas-batch 8081 | sed 's/.*://')" && \
	curl -fsS "http://localhost:$$POLICY_PORT/actuator/health/readiness" && \
	curl -fsS "http://localhost:$$BATCH_PORT/actuator/health/readiness"
