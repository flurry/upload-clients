#!/usr/bin/python2.7
# Copyright Yahoo Inc. 2016, see LICENSE.txt for full details
"""
Flurry's Crash service can symbolicate the crashes reported by Flurry's SDK.
This script uploads the symbols required to properly symbolicate crashes from
iOS applications.

How to send iOS symbols
-----------------------

1. Ensure that your project is configured to build dSYM bundles.
2. Copy this script to the root of your project directory.
3. Add "flurry.config" to the root of your project. This is an ini style file.
   e.g.:

     [flurry]
     token=TOKEN
     api-key=API_KEY

4. Add a "Run Script" build phase to run this script.

Now whenever you build your application, this script will run and upload
your application's dSYMs bundles to Flurry's symbolication service.

Note: if you are compiling your application for the App Store and have Bitcode
enabled, you will need to download your dSYMs from iTunesConnect after you
have submitted your build and then manually upload them to Flurry.
"""


import sys

if sys.version_info < (2, 7):
    sys.exit("Python version >= 2.7.x required")

import ConfigParser                                                 # noqa: E402
import httplib                                                      # noqa: E402
import json                                                         # noqa: E402
import logging                                                      # noqa: E402
import os                                                           # noqa: E402
import ssl                                                          # noqa: E402
import shutil                                                       # noqa: E402
import tarfile                                                      # noqa: E402
import time                                                         # noqa: E402
import urllib2                                                      # noqa: E402
import urlparse                                                     # noqa: E402
import zipfile                                                      # noqa: E402
from argparse import ArgumentParser, RawDescriptionHelpFormatter    # noqa: E402
from tempfile import mkdtemp, NamedTemporaryFile                    # noqa: E402
from urllib2 import Request                                         # noqa: E402


METADATA_BASE = "https://crash-metadata.flurry.com/pulse/v1/"
UPLOAD_BASE = "https://upload.flurry.com/upload/v1/"

API_KEY = 'api-key'
TOKEN_KEY = 'token'
IS_PYTHON_2_7_9 = sys.version_info.micro >= 9

if IS_PYTHON_2_7_9:
    SSL_CONTEXT = ssl.create_default_context(purpose=ssl.Purpose.CLIENT_AUTH)
else:
    SSL_CONTEXT = None


LOG_FORMAT = "%(asctime)s [%(levelname)s] %(filename)s:%(lineno)s	%(message)s"
logging.basicConfig(format=LOG_FORMAT, datefmt="%H:%M:%S")
log = logging.getLogger("com.flurry.upload.client")


def find_dsyms_and_upload(token, api_key, search_path, single_file,
                          connect_archive, wait, max_wait):
    """
    Find the symbol files for a build and upload them to the Crash service

    token - the long lived token used to access Flurry's APIs
    api_key - the api key for the project
    search_path - the folder where the dSYMs to be uploaded are located
    single_file - if search_path refers to a single dSYM bundle
    connect_archive - if search_path refers to an archive downloaded
        from iTunesConnect
    wait - whether to wait for the upload to be processed
    max_wait - number of seconds to wait for the upload to be processed
    """
    log.info("fetching project")
    project = lookup_api_key(api_key, token)

    log.info("taring files")
    tar_path = ""
    if not connect_archive:
        tar_path = tar_gz_dsyms(search_path, single_file)
    else:
        tar_path = process_archive(search_path)
    tar_size = get_tar_size(tar_path)
    log.info("archive: %s (%d b)", tar_path, tar_size)

    log.info("creating upload")
    upload = create_upload(project, tar_size, token)

    log.info("uploading file")
    if not send_to_upload_service(project, upload, token, tar_path, tar_size):
        die("failed to send file to upload service")

    if wait:
        wait_for_upload_to_process(project, upload, token, max_wait)
    else:
        log.info("Skipping check to see if the dSYMs got processed")


def configure_arg_parser():
    """Parse the command line arguments.

    Return ArgumentParser namespace
    """

    dsym_default = os.environ.get("DWARF_DSYM_FOLDER_PATH") or ''

    parser = ArgumentParser(
        description=(
            "Upload dSYM files for use in Flurry's crash reporting.\n" +
            "You must specify either token and api-key or a configuration file."
        ),
        formatter_class=RawDescriptionHelpFormatter)

    cmd_line = parser.add_argument_group('Inline Configuration')
    cmd_line.add_argument(
        "-t", "--token",
        type=str, default="", help="A programmatic token")
    cmd_line.add_argument(
        "-k", "--api-key",
        type=str, default="", help="API key of your project")

    config_file = parser.add_argument_group('File-based Configuration')
    config_file.add_argument(
        "-c", "--config-file",
        type=str, help="an INI file that has your API key and Token")

    path_config = parser.add_argument_group('Search Path Configuration')
    path_config.add_argument(
        "-p", "--search-path",
        type=str, default=dsym_default,
        help=(
            "The path to search for dSYMs, the path to a particular dSYM, " +
            "or the path to an archive downloaded from iTunesConnect " +
            "(Default: $DWARF_DSYM_FOLDER_PATH)"
        )
    )

    behavior = parser.add_argument_group('Script behavior')
    log_levels = [n for n in logging._levelNames if isinstance(n, basestring)]
    behavior.add_argument(
        "--log", type=str, help="Set logging level", metavar="LEVEL",
        default=logging.INFO, choices=log_levels)
    behavior.add_argument(
        "--insecure", default=False, action="store_const", const=True,
        help="Ignore SSL when talking to the API")
    behavior.add_argument(
        "--no-wait", default=False, action="store_const", const=True,
        help="Ignore SSL when talking to the API")
    behavior.add_argument(
        "--max-wait", type=int, default=600,
        help=(
            "The maximum number of seconds to wait for the upload to get " +
            "processed before failing"
        )
    )

    return parser


def lookup_api_key(apikey, token):
    """Given the API key for a project, retrieve the project's id

    apikey - the API key for the project
    token - the token for the metatdata service

    return the project id
    """
    url = metadata_url(
        "project?filter[project.apiKey]={apikey}&fields[project]=apiKey",
        apikey=apikey
    )
    headers = json_api_headers(token)
    request = create_request(url, headers, data=None)
    response = exec_request(request, "looking up project")
    project = json.loads(response.read())
    if len(project["data"]) == 0:
        die("Invalid Api Key")
    log.debug("project: %s", project)
    return int(project["data"][0]["id"])


def tar_gz_dsyms(search_path, single_file):
    """Tar and gz all of the dSYMs found in the provided directory

    search_path - the path of the dSYM root
    single-file - a flag that indicates that search_path points
        to a .dSYM directory

    return the path to the archive
    """
    cwd = os.getcwd()

    search_root = os.path.abspath(search_path)
    if single_file:
        dsyms = [os.path.basename(search_root)]
        search_root = os.path.dirname(search_root)
    else:
        log.debug("looking for dsyms in %s", search_root)
        dsyms = [f for f in os.listdir(search_root) if f.endswith(".dSYM")]

    tmpf = NamedTemporaryFile(delete=False)
    tmpf.close()
    tar_path = os.path.abspath(tmpf.name + ".tgz")

    os.chdir(search_root)
    tar = tarfile.open(name=tar_path, mode="w:gz")
    for dsym in dsyms:
        log.debug("Adding %s to tar", dsym)
        tar.add(dsym)
    tar.close()

    os.chdir(cwd)

    return tar_path


def process_archive(archive_path):
    scratch_dir = mkdtemp()
    log.info("processing file as iTunesConnect archive")
    log.debug("expanding to directory %s", scratch_dir)
    with zipfile.ZipFile(archive_path, "r") as archive:
        archive.extractall(scratch_dir)
    tar_path = tar_gz_dsyms(scratch_dir, False)

    shutil.rmtree(scratch_dir, True)
    return tar_path


def get_tar_size(tar_path):
    """Calcualte the size of the archive to upload

    return the size in bytes
    """
    return os.path.getsize(tar_path)


def create_upload(project, size, token):
    """Create an upload for this dSYM file

    project - the id of the current project
    size - the size of the dsym to upload
    token - the zuul token to use

    return the id of the upload that was created
    """
    url = metadata_url("/project/{:d}/uploads", project)
    upload = {
        "data": {
            "type": "upload",
            "attributes": {
                "uploadType": "IOS",
                "contentLength": size,
            },
            "relationships": {
                "project": {
                    "data": {
                        "id": project,
                        "type": "project"
                    }
                }
            }
        }
    }
    request = create_request(url, json_api_headers(token), json.dumps(upload))
    response = exec_request(request, "creating upload")
    upload = json.loads(response.read())
    log.debug("Created upload %s", upload)
    return int(upload["data"]["id"])


def send_to_upload_service(project, upload, token, tar_path, tar_size):
    """Send the archive to the upload service

    project - the id of the dsym's project
    upload - the id of the upload
    token - the zuul token to use
    tar_path - the path to the dSYM archive

    return true if we get 201/202, false otherwise
    """
    url = upload_url(project, upload)
    headers = {
        "Content-Type": "application/octet-stream",
        "Range": "bytes 0-{}".format(tar_size - 1),
        "Content-Length": tar_size,
        "Authorization": "Bearer {}".format(token)
    }
    request = create_request(url, headers, open(tar_path, "rb"))
    response = exec_request(request, "uploading file", 600)
    return (response.code == 201) or (response.code == 202)


def wait_for_upload_to_process(project, upload, token, max_wait):
    """Poll the metadata service to check and see if the upload was processed

    project - the id of the project
    upload - the id of the upload
    token - the token authorized for the upload service
    max_wait - the number of seconds to wait for the upload to be processed
    """
    max_iterations = max_wait / 5
    iterations = 0
    status = ""
    reason = ""
    while True:
        (status, reason) = get_upload_status(project, upload, token)
        iterations += 1
        status_msg = "Unknown"

        if status == "COMPLETED":
            break
        elif status == "FAILED" or iterations > max_iterations:
            reason = (
                "Timed out waiting for upload to be processed." if
                iterations > max_iterations else reason)
            die(
                "Upload was not processed. If this issue persists please" +
                " contact Flurry Support\nError: {}", reason)
        elif status == "UPLOADING":
            status_msg = "Waiting"
        elif status == "UPLOADED":
            status_msg = "In Queue"

        log.info("Upload status: %s", status_msg)
        time.sleep(5)

    if reason is not None and len(reason) > 0:
        reason = "(%s)" % reason

    log.info("Successfully uploaded and processed dSYM files %s", reason)


def get_upload_status(project, upload, token):
    """Fetch the status of an upload from the metatdata service

    project - the project the upload belongs to
    upload - the upload to check
    token - the token for the metatdata service

    return a tuple of the upload's status and error message
    """
    url = metadata_url(
        "project/{}/uploads/{}?fields[upload]=uploadStatus,failureReason",
        project, upload)
    request = create_request(url, json_api_headers(token), data=None)
    response = exec_request(request, "checking upload status")
    upload = json.loads(response.read())
    log.debug("upload: %s", upload)
    attrs = upload["data"]["attributes"]
    status = attrs["uploadStatus"]
    reason = attrs.get("failureReason") or ''
    return (status, reason)


def json_api_headers(token):
    """Return standard headers for talking to the metadata service"""
    return {
        "Authorization": "Bearer {}".format(token),
        "Accept": "application/vnd.api+json",
        "Content-Type": "application/vnd.api+json"
    }


def format_url(base, resource, *args, **kwargs):
    """Format and normalize urls"""
    url = resource.format(*args, **kwargs).lstrip('/')
    return urlparse.urljoin(base, url)


def upload_url(project_id, upload_id):
    """Create the url to upload blobs to in the upload service"""
    return format_url(UPLOAD_BASE, "upload/{:d}/{:d}", project_id, upload_id)


def metadata_url(resource, *args, **kwargs):
    """Create a url to interact with the metatdata service"""
    return format_url(METADATA_BASE, resource, *args, **kwargs)


def create_request(url, headers, data):
    """Create a http request object with headers and payload

    url - the url for the request
    headers - a map of headers for the request
    data - the request's payload

    return a configured Request object
    """
    request = Request(url, data=data)
    for header_name in headers:
        request.add_header(header_name, headers[header_name])
    return request


def exec_request(request, task, timeout=10):
    """Execute the request and handle HTTP failures

    request - the request to fire
    task - the name of the task being executed

    return the urllib2 response
    """
    kwargs = {
        "timeout": timeout
    }
    if IS_PYTHON_2_7_9:
        kwargs["context"] = SSL_CONTEXT

    try:
        return urllib2.urlopen(request, **kwargs)
    except urllib2.HTTPError as e:
        body = e.read()
        if e.code == 401:
            body = "UNAUTHORIZED (Bad Token)"
        log.debug("%s =>\n%s", e.url, body)
        die(
            "error {task}. ({method} {url} => {code:d}) {body}",
            task=task,
            method=request.get_method(),
            url=e.url,
            code=e.code,
            body=body)
    except httplib.HTTPException as e:
        die("error {task}. {err} {errtype}", task=task, err=e, errtype=type(e))
    except urllib2.URLError:
        # Retry after first failure
        try:
            return urllib2.urlopen(request, **kwargs)
        except urllib2.URLError as e:
            die("error {task}. {err} {errtype}", task=task, err=e, errtype=type(e))

def die(fmt, *args, **kwargs):
    """exit the script and print an error to the console"""
    sys.exit(fmt.format(*args, **kwargs))


def get_flurry_config_key(config, key, default=None):
    try:
        return config.get("flurry", key)
    except (ConfigParser.NoSectionError, ConfigParser.NoOptionError):
        return default


def main():
    parser = configure_arg_parser()
    args = parser.parse_args()
    log.setLevel(args.log)

    insecure = args.insecure

    if IS_PYTHON_2_7_9 and insecure:
        SSL_CONTEXT.check_hostname = False
        SSL_CONTEXT.verify_mode = ssl.CERT_NONE

    token = args.token
    api_key = args.api_key
    search_path = args.search_path
    wait = not args.no_wait
    max_wait = args.max_wait
    single = search_path.endswith(".dSYM")
    archive = search_path.endswith(".zip")

    if args.config_file:
        config = ConfigParser.RawConfigParser()
        config.read(args.config_file)
        token = get_flurry_config_key(config, TOKEN_KEY, token)
        api_key = get_flurry_config_key(config, API_KEY, api_key)

    log.debug("--------------------")
    log.debug("apiKey=%s", api_key)
    log.debug("search_path=%s", search_path)
    log.debug("wait=%s", wait)
    log.debug("max_wait=%s", max_wait)
    log.debug("singe_file=%b", single)
    log.debug("connect_archive=%b", archive)
    log.debug("--------------------")

    if not token or not api_key:
        die(
            "{usage}\nYou must provide a token [-t TOKEN] ({token_present}) and an API key " +
            "[-k API_KEY] ({api_key_present})\nor a config file [-c CONFIG] ({config_specified}) " +
            "that contains your API key and token for the upload.",
            token_present="found" if bool(token) else "not found",
            api_key_present="found" if bool(api_key) else "not found",
            config_specified="found" if bool(args.config_file) else "not found",
            usage=parser.format_usage())

    find_dsyms_and_upload(token, api_key, search_path, single, archive,
                          wait=wait, max_wait=max_wait)


if __name__ == "__main__":
    main()
