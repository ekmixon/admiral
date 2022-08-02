#!/usr/bin/env python3

import io
import traceback
import datetime
import time
import os.path
import subprocess
import os
import json
import sys
import importlib
import zipfile
from urllib.parse import urlparse
import requests

SRC_DIR = './user_scripts'
SRC_REQ_FILE = 'requirements.txt'
TRUSTED_CERTS = '/app/trust.pem'

use_custom_ca=True
trust_strategy_set=False

def save_source_in_file(closure_description, module_name):
    src_file = None
    try:
        if not os.path.exists(SRC_DIR):
            os.makedirs(SRC_DIR)
        src_file = open(SRC_DIR + os.sep + module_name + '.ps1', "w")
    except Exception as err:
        sys.stderr.write(f'ERROR: Unable to save source file {str(err)}n')
        raise err
    else:
        # print 'Saving powershell script: {}'.format(closure_description['source'])
        src_file.write(closure_description['source'])
    finally:
        src_file.close()

def patch_results(outputs, closure_semaphore, token):
    # print 'Results to be patched: {}'.format(result)
    closure_uri = os.environ['TASK_URI']
    headers = {'Content-type': 'application/json',
               'Accept': 'application/json',
               'x-xenon-auth-token': token
               }
    state = "FINISHED"
    data = {
        "state": state,
        "closureSemaphore": closure_semaphore,
        "outputs": outputs
    }
    patch_resp = dynamic_wrapper('patch', closure_uri, headers, json.dumps(data))
    if patch_resp.ok:
        print(f'Script run state: {state}')
    else:
        patch_resp.raise_for_status()


def execute_saved_source(closure_uri, inputs, outputsName, closure_semaphore, module_name, handler_name):
    print ('Script run logs:')
    print ('*******************')
    try:
        sys.path.append(os.path.abspath(SRC_DIR))
        token = os.environ['TOKEN']

        os.environ['TOKEN'] = ''
        source_name = f'-source_name {module_name}.ps1'
        input = str(inputs)
        outputs = '-outputs "{}"'
        closure_sem = '-closure_semaphore "' + str(closure_semaphore) + '"'
        closure_uri = '-closure_uri "' + str(closure_uri) + '"'
        closure_token = '-token "' + token + '"'
        handler_name = '-handler_name "' + handler_name + '"'
        trusted_certs = '-trusted_certs "' + TRUSTED_CERTS + '"'

        (out, err) = subprocess.Popen('/usr/bin/powershell -file ../context_object_class.ps1 "'
                                      + input + '" ' + outputs + ' ' + closure_sem + ' '
                                      + closure_uri+ ' ' + closure_token + ' ' + source_name
                                      + ' ' + handler_name + ' ' + trusted_certs,
                                      shell=True, universal_newlines=True, stderr=subprocess.PIPE,
                                      stdout=subprocess.PIPE).communicate()

        print (out)
        if err:
            print ('*******************')
            print ('Script run failed with: ', err)
            patch_failure(closure_semaphore, err, token)
            exit(1)
        outputName = ''.join(outputsName)
        output = save_output() if outputName.isalpha() else {}
        print ('*******************')
        patch_results(output, closure_semaphore, token)
    except Exception as ex:
        print ('*******************')
        print ('Script run failed with: ', ex)
        patch_failure(closure_semaphore, ex, token)
        exit(1)
    finally:
        print ('Script run completed at: {0}'.format(datetime.datetime.now()))

def save_output ():
    output = open('output.txt', "r")
    outputValue = output.read()
    outputValue = json.loads(outputValue)
    return outputValue

def download_and_save_source(source_url, module_name):
    if not os.path.exists(SRC_DIR):
        os.makedirs(SRC_DIR)
    # print 'Downloading source from: ', source_url
    resp = requests.get(source_url, stream=True, verify = TRUSTED_CERTS)
    content_type = resp.headers['content-type']
    if resp.status_code != 200:
        raise Exception('Unable to fetch script source from: ', source_url)
    # print 'Type of source content: ', content_type
    if 'application/zip' in content_type or 'application/octet-stream' in content_type:
        print ('Processing ZIP source file...')
        sip_content = zipfile.ZipFile(io.BytesIO(resp.content))
        sip_content.extractall(SRC_DIR)
    else:
        chunk_size = 10 * 1024
        with open(f'{os.path.join(SRC_DIR, module_name)}.ps1', 'wb') as file_dest:
            for chunk in resp.iter_content(chunk_size):
                file_dest.write(chunk)

def create_entry_point(closure_description):
    handler_name = closure_description['name']
    if 'entrypoint' in closure_description:
        if entry_point := closure_description['entrypoint']:
            entries = entry_point.rsplit('.', 1)
            return entries[0], entries[1]
        else:
            return 'index', handler_name
    else:
        print(
            f'Entrypoint is empty. Will use closure name for a handler name: {handler_name}'
        )

        return 'index', handler_name


def proceed_with_closure_description(closure_uri, closure_desc_uri, inputs, closure_semaphore, skip_execution):
    # print 'Downloading closure description from: {}'.format(closure_desc_uri)
    headers = {'Content-type': 'application/json',
               'Accept': 'application/json',
               'x-xenon-auth-token': os.environ['TOKEN']
               }
    closure_desc_response = dynamic_wrapper('get', closure_desc_uri, headers)
    if closure_desc_response.ok:
        closure_description = json.loads(closure_desc_response.content.decode('utf-8'))
        (module_name, handler_name) = create_entry_point(closure_description)
        outputsName = closure_description['outputNames']
        if 'sourceURL' in closure_description and (
            source_url := closure_description['sourceURL']
        ):
            download_and_save_source(source_url, module_name)
        else:
            save_source_in_file(closure_description, module_name)
        execute_saved_source(closure_uri, inputs, outputsName, closure_semaphore, module_name, handler_name)
    else:
        closure_desc_response.raise_for_status()


def build_closure_description_uri(closure_uri, closure_desc_link):
    # parsed_obj = urlparse(closure_uri)
    pattern = "/resources/closures/"
    uri_head = closure_uri.split(pattern,1)[0]
    return uri_head + closure_desc_link


def patch_closure_started(closure_uri, closure_semaphore):
    headers = {'Content-type': 'application/json',
               'Accept': 'application/json',
               'x-xenon-auth-token': os.environ['TOKEN']
               }
    state = "STARTED"
    data = {
        "state": state,
        "closureSemaphore": closure_semaphore
    }
    patch_resp = dynamic_wrapper('patch', closure_uri, headers, json.dumps(data))
    if not patch_resp.ok:
        patch_resp.raise_for_status()


def is_blank(my_string):
    return not (my_string and my_string.strip())


def detect_trust_strategy(uri, **headers):
    headers['verify']=TRUSTED_CERTS
    global trust_strategy_set
    trust_strategy_set = True
    try:
        response = requests.head(uri, **headers)
        if response.ok:
            return True
    except Exception as err:
        pass
    return False


def dynamic_wrapper(method, uri, headers, data=None):
    args = {
        "headers": headers
    }

    global use_custom_ca
    if not trust_strategy_set:
        use_custom_ca = detect_trust_strategy(uri, **args)

    if os.path.exists(TRUSTED_CERTS) and use_custom_ca:
        args['verify']=TRUSTED_CERTS

    if data:
        args['data']=data

    return getattr(requests, method)(uri, **args)


def proceed_with_closure_execution(skip_execution=False):
    closure_uri = os.environ['TASK_URI']

    if is_blank(closure_uri):
        print ('TASK_URI environment variable is not set. Aborting...')
        return

    # print 'Downloading closure from: {0}'.format(closure_uri)
    headers = {'Content-type': 'application/json',
               'Accept': 'application/json',
               'x-xenon-auth-token': os.environ['TOKEN']
               }
    closure_response = dynamic_wrapper('get', closure_uri, headers)
    if closure_response.ok:
        closure_data = json.loads(closure_response.content.decode('utf-8'))
        closure_semaphore = closure_data['closureSemaphore']
        if not skip_execution:
            # reinit general error handler
            setup_exc_handler(closure_semaphore)
            patch_closure_started(closure_uri, closure_semaphore)
        closure_inputs = closure_data['inputs'] if 'inputs' in closure_data else {}
        closure_desc_link = closure_data['descriptionLink']
        closure_desc_uri = build_closure_description_uri(closure_uri, closure_desc_link)
        proceed_with_closure_description(closure_uri, closure_desc_uri, closure_inputs, closure_semaphore, skip_execution)

    else:
        closure_response.raise_for_status()


def patch_failure(closure_semaphore, error, token=None):
    closure_uri = os.environ['TASK_URI']
    state = "FAILED"
    headers = {'Content-type': 'application/json',
               'Accept': 'application/json',
               'x-xenon-auth-token': token
               }
    if closure_semaphore is None:
        data = {
            "state": state,
            "errorMsg": repr(error)
        }
    else:
        data = {
            "state": state,
            "closureSemaphore": closure_semaphore,
            "errorMsg": repr(error)
        }

    patch_resp = dynamic_wrapper('patch', closure_uri, headers, json.dumps(data))
    if patch_resp.ok:
        print(f'Script run state: {state}')
    else:
        patch_resp.raise_for_status()


def setup_exc_handler(closure_semaphore):
    def handle_exception(exc_type, exc_value, exc_traceback):
        error = traceback.format_exception(exc_type, exc_value, exc_traceback)
        print ('Exception occurred: ', error)
        patch_failure(closure_semaphore, error, os.environ['TOKEN'])

    sys.excepthook = handle_exception