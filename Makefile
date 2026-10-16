.PHONY: compat compat-static compat-api compat-build cobol-test

compat: compat-static cobol-test compat-build compat-api

cobol-test:
	python3 -m unittest discover -s cobol/tests -v

compat-static:
	python3 compat/check_schema.py
	python3 compat/check_layouts.py
	python3 compat/check_security.py

compat-build:
	./compat/build_modules.sh

compat-api:
	./compat/api/run_facade.sh
