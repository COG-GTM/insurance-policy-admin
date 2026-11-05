.PHONY: compat compat-static compat-api compat-build inventory adr

compat: compat-static compat-build compat-api

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh

inventory:
	python3 tools/discovery/pas_inventory.py
	python3 -m unittest discover -s tools/discovery -p 'test_*.py'

adr:
	python3 tools/adr/check_adr.py
	python3 -m unittest discover -s tools/adr -p 'test_*.py'

# --- PAS target platform (pas/, AB-519). JDK 21 and Maven 3.9+ required; set PAS_JAVA_HOME if it is not the default. ---
.PHONY: pas pas-verify pas-build pas-format pas-env pas-up pas-smoke pas-api-contract facade-retirement pas-down pas-images pas-scan

PAS_JAVA_HOME ?= $(JAVA_HOME)
PAS_MVN = cd pas && JAVA_HOME="$(PAS_JAVA_HOME)" mvn -B -ntp
TRIVY_IMAGE ?= aquasec/trivy:0.67.2

# One command for a fresh clone: build, run every check and test, start the local stack, smoke-test it.
pas: pas-verify pas-up

# Formatting check, enforcer rules, SpotBugs, unit, integration (Testcontainers) and ArchUnit tests.
pas-verify:
	$(PAS_MVN) verify

pas-build:
	$(PAS_MVN) package -DskipTests -Dspotbugs.skip=true

pas-format:
	$(PAS_MVN) spotless:apply

# Creates the git-ignored .env with a random local database password (never committed).
pas-env:
	@./pas/scripts/init-env.sh

pas-up: pas-env pas-build
	docker compose up -d --build --wait
	./pas/scripts/smoke.sh

pas-smoke:
	./pas/scripts/smoke.sh

# Facade REST contract against the running local stack (loads the fictional facade seed rows).
pas-api-contract:
	./pas/scripts/api-contract.sh

# Facade retirement (AB-524): validate the gated plan, test the tool, print the dry run.
facade-retirement:
	python3 -m unittest discover -s tools/retirement -p 'test_*.py'
	python3 tools/retirement/facade_retirement.py --check
	python3 tools/retirement/facade_retirement.py

pas-down:
	docker compose --profile batch down

pas-images: pas-build
	docker build -f pas/Dockerfile --build-arg APP=pas-policy-service -t pas-policy-service:local pas
	docker build -f pas/Dockerfile --build-arg APP=pas-batch -t pas-batch:local pas

# Vulnerability scan of both images (OS packages and every jar), HIGH/CRITICAL fail the build.
pas-scan: pas-images
	for image in pas-policy-service:local pas-batch:local; do \
	  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$(CURDIR)/pas/.trivyignore:/.trivyignore:ro" \
	    -v "$${HOME}/.cache/trivy:/root/.cache/trivy" $(TRIVY_IMAGE) image --scanners vuln \
	    --severity HIGH,CRITICAL --ignorefile /.trivyignore --exit-code 1 --no-progress $$image || exit 1; \
	done

# --- COBOL characterization harness (parity/, AB-520). GnuCOBOL 3.x (cobc) for the COBOL side, JDK 21 for the Java side. ---
.PHONY: parity parity-check parity-record parity-java

# Both halves: the golden files are reproduced from COBOL, and Java matches them to the cent.
parity: parity-check parity-java

# Harness unit tests, registry/fixture/known-difference validation, COBOL re-run vs the golden files.
parity-check:
	python3 -m unittest discover -s tools/parity -p 'test_*.py'
	python3 tools/parity/parity.py check

# Re-record golden files from the COBOL run (all suites, or SUITE=<name>). Review the diff before committing.
parity-record:
	python3 tools/parity/parity.py record $(if $(SUITE),--suite $(SUITE))

# Java adapters against the golden files (pas/pas-parity); fails on any unexplained difference.
parity-java:
	$(PAS_MVN) -pl pas-parity -am verify

# --- AB-527 Claims and Actuarial extracts. ---
.PHONY: extract-parity

# Tool tests, cut-over register validation, and the pas-batch extract suites: 5 consecutive runs
# byte-identical to the frozen JCL SQL, real SFTP delivery with checksums and readback. With
# CAPTURED=<mainframe files> CANDIDATE=<pas-batch files> (one YYYY-MM-DD sub-directory per run)
# it also compares real mainframe output and needs 5 consecutive identical runs per feed.
extract-parity:
	python3 -m unittest discover -s tools/extracts -p 'test_*.py'
	python3 tools/extracts/extract_parity.py gate --check
	$(PAS_MVN) -pl pas-batch -am verify -Dtest='FeedLayoutTest,FeedDeliveryTest,SftpDestinationTest,SftpFeedTransferTest' -Dit.test='Extract*IT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false
ifdef CAPTURED
	python3 tools/extracts/extract_parity.py compare --captured "$(CAPTURED)" --candidate "$(CANDIDATE)" --report extract-parity-report.json
endif
