#!/usr/bin/env python

#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
import os
import sys
import traceback

import atlas_config as mc

METADATA_LOG_OPTS="-Datlas.log.dir=%s -Datlas.log.file=application.log"
METADATA_COMMAND_OPTS="-Datlas.home=%s"
METADATA_CONFIG_OPTS="-Datlas.conf=%s"
DEFAULT_JVM_OPTS="-Xmx1024m -XX:MaxPermSize=512m -Dlog4j.configuration=atlas-log4j.xml -Djava.net.preferIPv4Stack=true"
CONF_FILE="application.properties"
HBASE_STORAGE_CONF_ENTRY="atlas.graph.storage.backend\s*=\s*hbase"

def main():

    metadata_home = mc.metadataDir()
    confdir = mc.dirMustExist(mc.confDir(metadata_home))
    mc.executeEnvSh(confdir)
    logdir = mc.dirMustExist(mc.logDir(metadata_home))

    #create sys property for conf dirs
    jvm_opts_list = (METADATA_LOG_OPTS % logdir).split()

    cmd_opts = (METADATA_COMMAND_OPTS % metadata_home)
    jvm_opts_list.extend(cmd_opts.split())

    config_opts = (METADATA_CONFIG_OPTS % confdir)
    jvm_opts_list.extend(config_opts.split())

    default_jvm_opts = DEFAULT_JVM_OPTS
    metadata_jvm_opts = os.environ.get(mc.METADATA_OPTS, default_jvm_opts)
    jvm_opts_list.extend(metadata_jvm_opts.split())

    #expand web app dir
    web_app_dir = mc.webAppDir(metadata_home)
    mc.expandWebApp(metadata_home)

    #add hbase-site.xml to classpath
    hbase_conf_dir = mc.hbaseConfDir(confdir)

    p = os.pathsep
    metadata_classpath = confdir + p \
                       + os.path.join(web_app_dir, "atlas", "WEB-INF", "classes" ) + p \
                       + os.path.join(web_app_dir, "atlas", "WEB-INF", "lib", "atlas-titan-${project.version}.jar" ) + p \
                       + os.path.join(web_app_dir, "atlas", "WEB-INF", "lib", "*" )  + p \
                       + os.path.join(metadata_home, "libext", "*")
    if os.path.exists(hbase_conf_dir):
        metadata_classpath = metadata_classpath + p \
                            + hbase_conf_dir
    else: 
       storage_backend = mc.grep(os.path.join(confdir, CONF_FILE), HBASE_STORAGE_CONF_ENTRY)
       if storage_backend != None:
	   raise Exception("Could not find hbase-site.xml in %s. Please set env var HBASE_CONF_DIR to the hbase client conf dir", hbase_conf_dir)
    
    metadata_pid_file = mc.pidFile(metadata_home)
    
            
    if os.path.isfile(metadata_pid_file):
       #Check if process listed in atlas.pid file is still running
       pf = file(metadata_pid_file, 'r')
       pid = pf.read().strip()
       pf.close() 
       

       if  mc.ON_POSIX:
            
            if mc.unix_exist_pid((int)(pid)):
                mc.server_already_running(pid)
            else:
                 mc.server_pid_not_running(pid)
              
              
       else:
            if mc.IS_WINDOWS:
                if mc.win_exist_pid(pid):
                   mc.server_already_running(pid)
                else:
                     mc.server_pid_not_running(pid)
                   
            else:
                #os other than nt or posix - not supported - need to delete the file to restart server if pid no longer exist
                mc.server_already_running(pid)
             


    args = ["-app", os.path.join(web_app_dir, "atlas")]
    args.extend(sys.argv[1:])

    process = mc.java("org.apache.atlas.Atlas", args, metadata_classpath, jvm_opts_list, logdir)
    mc.writePid(metadata_pid_file, process)

    print "Apache Atlas Server started!!!\n"

if __name__ == '__main__':
    try:
        returncode = main()
    except Exception as e:
        print "Exception: %s " % str(e)
        print traceback.format_exc()
        returncode = -1

    sys.exit(returncode)
