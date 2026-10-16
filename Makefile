.PHONY: compat compat-static compat-api compat-build cobol-test

compat: compat-static compat-build compat-api

cobol-test:
	python3 cobol/tests/test_polrnw.py

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh
