#!/usr/bin/python
import os
import ssl
import json
import time
import urllib
import httplib
import tarfile
import urllib2
import logging
from sys import exit, argv
from urllib2 import Request
from argparse import ArgumentParser
from tempfile import NamedTemporaryFile

log = logging.getLogger("com.flurry.upload.client")
logging.basicConfig(format="%(filename)s:%(lineno)s	[%(levelname)s] %(message)s")
log.level = level=logging.INFO

SSL_CONTEXT = ssl.create_default_context()

def main():
    requests = [
        200,
        202,
        302,
        400,
        404,
        500,
        999
    ]

    for code in requests:
        req = _create_request(code)
        res = _exec_request(req, "testing status %d" % code)


def _create_request(code):
    url = "http://localhost:8080/status/%d" % code
    return Request(url)


def _exec_request(request, task, timeout=10):
    try:
        return urllib2.urlopen(request, context=SSL_CONTEXT, timeout=timeout)
    except urllib2.HTTPError, e:
        print "error %s. (%s %s => %d) %s" % (task, request.get_method(), e.url, e.code, e.read())
        print dir(e)
    except httplib.HTTPException, e:
        print "error %s. %s" % (task, str(e))
        print dir(e)

if __name__ == '__main__':
    main()
