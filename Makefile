all: lint

lint: dev/bin/flake8
	dev/bin/python -m flake8 xcode/upload-symbols.py

dev/bin/flake8: dev/.done
	dev/bin/pip install flake8

dev/.done:
	virtualenv-2.7 dev
	touch dev/.done


.PHONY: all lint
