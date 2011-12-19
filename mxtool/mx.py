#!/usr/bin/python
#
# ----------------------------------------------------------------------------------------------------
#
# Copyright (c) 2007, 2011, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#
# ----------------------------------------------------------------------------------------------------
#
# mx is a command line tool inspired by mvn (http://maven.apache.org/)
# and hg (http://mercurial.selenic.com/). It includes a mechanism for
# managing the dependencies between a set of projects (like Maven)
# as well as making it simple to run commands
# (like hg is the interface to the Mercurial commands).
#
# When launched, mx looks for a mx configuration (i.e. a directory named 'mx') 
# in the current working directory. This is the primary mx configuration. Any
# other mx configurations are included mx configurations.
#
# If an mx configuration exists, then the following files in the configuration
# are processed (if they exist):
#
#   projects    - Lists projects, libraries and dependencies between them
#   commands.py - Extensions to the commands launchable by mx. This is only processed
#                 for the primary mx configuration.
#   includes    - Other directories containing mx configurations to be loaded.
#                 This is a recursive action. 
#   env         - A set of environment variable definitions.
#
# The MX_INCLUDES environment variable can also be used to specify
# other directories containing mx configurations.
# This value of this variable has the same format as a Java class path.
#
# The includes and env files are typically not put under version control
# as they usually contain local filesystem paths.
#
# The projects file is like the pom.xml file from Maven except that
# it is in a properties file format instead of XML. Each non-comment line
# in the file specifies an attribute of a project or library. The main 
# difference between a project and a library is that the former contains
# source code built by the mx tool where as the latter is an external
# dependency. The format of the projects file is 
#
# Library specification format:
#
#     library@<name>@<prop>=<value>
#
# Built-in library properties (* = required):
#
#    *path: the file system path for the library to appear on a class path
#     urls: a comma seperated list of URLs from which the library can be downloaded
#     optional: if "true" then this library will be omitted from a class path if it doesn't exist on the file system and no URLs are specified
#
# Project specification format:
#
#     project@<name>@<prop>=<value>
#
# The name of a project also denotes the directory it is in.
#
# Built-in project properties:
#
#    *sourceDirs: a comma separated list of source directoriy names (relative to the project directory)
#     dependencies: a comma separated list of the libraries and project the project depends upon (transitive dependencies may be omitted)
#     checkstyle: the project whose Checkstyle configuration (i.e. <project>/.checkstyle_checks.xml) is used
#
# Other properties can be specified for projects and libraries for use by extension commands.
#
# Values can use environment variables with Bash syntax (e.g. ${HOME}).

import sys
import os
import subprocess
from collections import Callable
from threading import Thread
from argparse import ArgumentParser, REMAINDER
from os.path import join, dirname, exists, getmtime, isabs, expandvars, isdir
import shlex
import types
import urllib2
import contextlib
import StringIO
import zipfile
import shutil, fnmatch, re, xml.dom.minidom

DEFAULT_JAVA_ARGS = '-ea -Xss2m -Xmx1g'

_projects = dict()
_libs = dict()
_suites = dict()

""" Options parsed from the command line. """
_opts = None

""" Prefix for a Java command. """
_java = None

"""
A dependency is a library or project specified in a suite.
"""
class Dependency:
    def __init__(self, suite, name):
        self.name = name
        self.suite = suite
        
    def __str__(self):
        return self.name
    
    def __eq__(self, other):
        return self.name == other.name
    
    def __ne__(self, other):
        return self.name != other.name

    def __hash__(self):
        return hash(self.name)
    
    def isLibrary(self):
        return isinstance(self, Library)
    
class Project(Dependency):
    def __init__(self, suite, name, srcDirs, deps, dir):
        Dependency.__init__(self, suite, name)
        self.srcDirs = srcDirs
        self.deps = deps
        self.checkstyleProj = name
        self.native = False
        self.dir = dir
        
    def all_deps(self, deps, includeLibs):
        if self in deps:
            return deps
        for name in self.deps:
            assert name != self.name
            dep = _libs.get(name, None)
            if dep is not None:
                if includeLibs and not dep in deps:
                    deps.append(dep)
            else:
                dep = project(name)
                if not dep in deps:
                    dep.all_deps(deps, includeLibs)
        if not self in deps:
            deps.append(self)
        return deps
    
    def _compute_max_dep_distances(self, name, distances, dist):
        currentDist = distances.get(name);
        if currentDist is None or currentDist < dist:
            distances[name] = dist
            p = project(name, False)
            if p is not None:
                for dep in p.deps:
                    self._compute_max_dep_distances(dep, distances, dist + 1)
                
    def canonical_deps(self):
        """
        Get the dependencies of this project that are not recursive (i.e. cannot be reached
        via other dependencies).
        """
        distances = dict()
        result = set()
        self._compute_max_dep_distances(self.name, distances, 0)
        for n,d in distances.iteritems():
            assert d > 0 or n == self.name
            if d == 1:
                result.add(n)
                
            
        if len(result) == len(self.deps) and frozenset(self.deps) == result:
            return self.deps
        return result;
    

    def source_dirs(self):
        """
        Get the directories in whihc the sources of this project are found.
        """
        return [join(self.dir, s) for s in self.srcDirs]
        
    def output_dir(self):
        """
        Get the directory in which the class files of this project are found.
        """
        if self.native:
            return None
        return join(self.dir, 'bin')

    def append_to_classpath(self, cp, resolve):
        if not self.native:
            cp.append(self.output_dir())

class Library(Dependency):
    def __init__(self, suite, name, path, mustExist, urls):
        Dependency.__init__(self, suite, name)
        self.path = path
        self.urls = urls
        self.mustExist = mustExist
    
    def get_path(self, resolve):
        path = self.path
        if not isabs(path):
            path = join(self.suite.dir, path)
        if resolve and self.mustExist and not exists(path):
            assert not len(self.urls) == 0, 'cannot find required library  ' + self.name + " " + path;
            download(path, self.urls)
        return path
        
    def append_to_classpath(self, cp, resolve):
        path = self.get_path(resolve)
        if exists(path) or not resolve:
            cp.append(path)
    
class Suite:
    def __init__(self, dir, primary):
        self.dir = dir
        self.projects = []
        self.libs = []
        self.includes = []
        self.commands = None
        self._load(join(dir, 'mx'), primary=primary)

    def _load_projects(self, mxDir):
        libsMap = dict()
        projsMap = dict() 
        projectsFile = join(mxDir, 'projects')
        if not exists(projectsFile):
            return
        with open(projectsFile) as f:
            for line in f:
                line = line.strip()
                if len(line) != 0 and line[0] != '#':
                    key, value = line.split('=', 1)
                    
                    parts = key.split('@')
                    
                    if len(parts) == 2:
                        pass
                    if len(parts) != 3:
                        abort('Property name does not have 3 parts separated by "@": ' + key)
                    kind, name, attr = parts
                    if kind == 'project':
                        m = projsMap
                    elif kind == 'library':
                        m = libsMap
                    else:
                        abort('Property name does not start with "project@" or "library@": ' + key)
                        
                    attrs = m.get(name)
                    if attrs is None:
                        attrs = dict()
                        m[name] = attrs
                    value = expandvars_in_property(value)
                    attrs[attr] = value
                        
        def pop_list(attrs, name):
            v = attrs.pop(name, None)
            if v is None or len(v.strip()) == 0:
                return []
            return [n.strip() for n in v.split(',')]
        
        for name, attrs in projsMap.iteritems():
            srcDirs = pop_list(attrs, 'sourceDirs')
            deps = pop_list(attrs, 'dependencies')
            subDir = attrs.pop('subDir', None);
            if subDir is None:
                dir = join(self.dir, name)
            else:
                dir = join(self.dir, subDir, name)
            p = Project(self, name, srcDirs, deps, dir)
            p.checkstyleProj = attrs.pop('checkstyle', name)
            p.native = attrs.pop('native', '') == 'true'
            p.__dict__.update(attrs)
            self.projects.append(p)

        for name, attrs in libsMap.iteritems():
            path = attrs['path']
            mustExist = attrs.pop('optional', 'false') != 'true'
            urls = pop_list(attrs, 'urls')
            l = Library(self, name, path, mustExist, urls)
            l.__dict__.update(attrs)
            self.libs.append(l)
        
    def _load_commands(self, mxDir):
        commands = join(mxDir, 'commands.py')
        if exists(commands):
            # temporarily extend the Python path
            sys.path.insert(0, mxDir)
    
            mod = __import__('commands')
    
            # revert the Python path
            del sys.path[0]

            if not hasattr(mod, 'mx_init'):
                abort(commands + ' must define an mx_init(env) function')
                
            mod.mx_init()
            self.commands = mod
                
    def _load_includes(self, mxDir):
        includes = join(mxDir, 'includes')
        if exists(includes):
            with open(includes) as f:
                for line in f:
                    self.includes.append(expandvars_in_property(line.strip()))
        
    def _load_env(self, mxDir):
        e = join(mxDir, 'env')
        if exists(e):
            with open(e) as f:
                for line in f:
                    line = line.strip()
                    if len(line) != 0 and line[0] != '#':
                        key, value = line.split('=', 1)
                        os.environ[key.strip()] = expandvars_in_property(value.strip())
        
    def _load(self, mxDir, primary):
        self._load_includes(mxDir)
        self._load_projects(mxDir)
        self._load_env(mxDir)
        if primary:
            self._load_commands(mxDir)
        
def get_os():
    if sys.platform.startswith('darwin'):
        return 'darwin'
    elif sys.platform.startswith('linux'):
        return 'linux'
    elif sys.platform.startswith('sunos'):
        return 'solaris'
    elif sys.platform.startswith('win32') or sys.platform.startswith('cygwin'):
        return 'windows'
    else:
        abort('Unknown operating system ' + sys.platform)

def _loadSuite(dir, primary=False):
    mxDir = join(dir, 'mx')
    if not exists(mxDir) or not isdir(mxDir):
        return
    if not _suites.has_key(dir):
        suite = Suite(dir, primary)
        _suites[dir] = suite 
        for p in suite.projects:
            existing = _projects.get(p.name)
            if existing is not None:
                abort('cannot override project  ' + p.name + ' in ' + p.dir + " with project of the same name in  " + existing.dir)
            _projects[p.name] = p
        for l in suite.libs:
            existing = _libs.get(l.name)
            if existing is not None:
                abort('cannot redefine library  ' + l.name)
            _libs[l.name] = l

def suites():
    """
    Get the list of all loaded suites.
    """
    return _suites.values()

def projects():
    """
    Get the list of all loaded projects.
    """
    return _projects.values()
    
def project(name, fatalIfMissing=True):
    """
    Get the project for a given name. This will abort if the named project does
    not exist and 'fatalIfMissing' is true.
    """
    p = _projects.get(name)
    if p is None and fatalIfMissing:
        abort('project named ' + name + ' not found')
    return p

def library(name, fatalIfMissing=True):
    """
    Gets the library for a given name. This will abort if the named library does
    not exist and 'fatalIfMissing' is true.
    """
    l = _libs.get(name)
    if l is None and fatalIfMissing:
        abort('library named ' + name + ' not found')
    return l

def _as_classpath(deps, resolve):
    cp = []
    if _opts.cp_prefix is not None:
        cp = [_opts.cp_prefix]
    for d in deps:
        d.append_to_classpath(cp, resolve)
    if _opts.cp_suffix is not None:
        cp += [_opts.cp_suffix]
    return os.pathsep.join(cp)

def classpath(names=None, resolve=True):
    """
    Get the class path for a list of given projects, resolving each entry in the
    path (e.g. downloading a missing library) if 'resolve' is true.
    """
    if names is None:
        return _as_classpath(sorted_deps(True), resolve)
    deps = []
    if isinstance(names, types.StringTypes):
        project(names).all_deps(deps, True)
    else:
        for n in names:
            project(n).all_deps(deps, True)
    return _as_classpath(deps, resolve)
    
def sorted_deps(includeLibs=False):
    """
    Gets the loaded projects and libraries sorted such that dependencies
    are before the projects that depend on them. Unless 'includeLibs' is
    true, libraries are omitted from the result.
    """
    deps = []
    for p in _projects.itervalues():
        p.all_deps(deps, includeLibs)
    return deps

class ArgParser(ArgumentParser):

    # Override parent to append the list of available commands
    def format_help(self):
        return ArgumentParser.format_help(self) + _format_commands()
    
    
    def __init__(self):
        self.java_initialized = False
        ArgumentParser.__init__(self, prog='mx')
    
        self.add_argument('-v', action='store_true', dest='verbose', help='enable verbose output')
        self.add_argument('-d', action='store_true', dest='java_dbg', help='make Java processes wait on port 8000 for a debugger')
        self.add_argument('--cp-pfx', dest='cp_prefix', help='class path prefix', metavar='<arg>')
        self.add_argument('--cp-sfx', dest='cp_suffix', help='class path suffix', metavar='<arg>')
        self.add_argument('--J', dest='java_args', help='Java VM arguments (e.g. --J @-dsa)', metavar='@<args>', default=DEFAULT_JAVA_ARGS)
        self.add_argument('--Jp', action='append', dest='java_args_pfx', help='prefix Java VM arguments (e.g. --Jp @-dsa)', metavar='@<args>', default=[])
        self.add_argument('--Ja', action='append', dest='java_args_sfx', help='suffix Java VM arguments (e.g. --Ja @-dsa)', metavar='@<args>', default=[])
        self.add_argument('--user-home', help='users home directory', metavar='<path>', default=os.path.expanduser('~'))
        self.add_argument('--java-home', help='JDK installation directory (must be JDK 6 or later)', metavar='<path>', default=_default_java_home())
        
    def _parse_cmd_line(self, args=None):
        if args is None:
            args = sys.argv[1:]

        self.add_argument('commandAndArgs', nargs=REMAINDER, metavar='command args...')
        
        opts = self.parse_args()
        
        if opts.java_home is None or opts.java_home == '':
            abort('Could not find Java home. Use --java-home option or ensure JAVA_HOME environment variable is set.')

        if opts.user_home is None or opts.user_home == '':
            abort('Could not find user home. Use --user-home option or ensure HOME environment variable is set.')
    
        os.environ['JAVA_HOME'] = opts.java_home
        os.environ['HOME'] = opts.user_home
 
        opts.java = join(opts.java_home, 'bin', 'java')
        opts.javac = join(opts.java_home, 'bin', 'javac')
        opts.javap = join(opts.java_home, 'bin', 'javap')
        
        commandAndArgs = opts.__dict__.pop('commandAndArgs')
        
        for s in suites():
            mod = s.commands
            if hasattr(mod is not None and mod, 'mx_post_parse_cmd_line'):
                mod.mx_post_parse_cmd_line(opts)
                
        return opts, commandAndArgs
    
def _format_commands():
    msg = '\navailable commands:\n\n'
    for cmd in sorted(commands.iterkeys()):
        c, _ = commands[cmd][:2]
        doc = c.__doc__
        if doc is None:
            doc = ''
        msg += ' {0:<20} {1}\n'.format(cmd, doc.split('\n', 1)[0])
    return msg + '\n'

def java_home():
    return _opts.java_home

def java_exe():
    return exe_suffix(_opts.java)

def java_args():
    init_java()
    return _opts.java_args

def format_java_cmd(args):
    init_java()
    return _java + args
    
def run_java(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    return run(format_java_cmd(args), nonZeroIsFatal=nonZeroIsFatal, out=out, err=err, cwd=cwd)

def run(args, nonZeroIsFatal=True, out=None, err=None, cwd=None):
    """
    Run a command in a subprocess, wait for it to complete and return the exit status of the process.
    If the exit status is non-zero and `nonZeroIsFatal` is true, then the program is exited with
    the same exit status.
    Each line of the standard output and error streams of the subprocess are redirected to the
    provided out and err functions if they are not None.
    """
    
    assert isinstance(args, types.ListType), "'args' must be a list: " + str(args)
    for arg in args:
        assert isinstance(arg, types.StringTypes), 'argument is not a string: ' + str(arg)
    
    if _opts.verbose:
        log(' '.join(args))
        
    try:
        if out is None and err is None:
            retcode = subprocess.call(args, cwd=cwd)
        else:
            def redirect(stream, f):
                for line in iter(stream.readline, ''):
                    f(line)
                stream.close()
            p = subprocess.Popen(args, cwd=cwd, stdout=None if out is None else subprocess.PIPE, stderr=None if err is None else subprocess.PIPE)
            if out is not None:
                t = Thread(target=redirect, args=(p.stdout, out))
                t.daemon = True # thread dies with the program
                t.start()
            if err is not None:
                t = Thread(target=redirect, args=(p.stderr, err))
                t.daemon = True # thread dies with the program
                t.start()
            retcode = p.wait()
    except OSError as e:
        log('Error executing \'' + ' '.join(args) + '\': ' + str(e))
        if _opts.verbose:
            raise e
        abort(e.errno)
    

    if retcode and nonZeroIsFatal:
        if _opts.verbose:
            raise subprocess.CalledProcessError(retcode, ' '.join(args))
        abort(retcode)
        
    return retcode

def exe_suffix(name):
    """
    Gets the platform specific suffix for an executable 
    """
    if os == 'windows':
        return name + '.exe'
    return name

def init_java():
    """
    Lazy initialization and preprocessing of this object's fields before running a Java command.
    """
    global _java
    if _java is not None:
        return

    def delAtAndSplit(s):
        return shlex.split(s.lstrip('@'))

    _opts.java_args = delAtAndSplit(_opts.java_args)
    _opts.java_args_pfx = sum(map(delAtAndSplit, _opts.java_args_pfx), [])
    _opts.java_args_sfx = sum(map(delAtAndSplit, _opts.java_args_sfx), [])
    
    # Prepend the -d64 VM option only if the java command supports it
    output = ''
    java = _opts.java
    try:
        
        output = subprocess.check_output([java, '-d64', '-version'], stderr=subprocess.STDOUT)
        _opts.java_args = ['-d64'] + _opts.java_args
    except subprocess.CalledProcessError as e:
        try:
            output = subprocess.check_output([java, '-version'], stderr=subprocess.STDOUT)
        except subprocess.CalledProcessError as e:
            print e.output
            abort(e.returncode)

    output = output.split()
    assert output[0] == 'java' or output[0] == 'openjdk'
    assert output[1] == 'version'
    version = output[2]
    if not version.startswith('"1.6') and not version.startswith('"1.7'):
        abort('Requires Java version 1.6 or 1.7, got version ' + version)

    if _opts.java_dbg:
        _opts.java_args += ['-Xdebug', '-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000']
        
    _java = [java] + _opts.java_args_pfx + _opts.java_args + _opts.java_args_sfx 
    
def _default_java_home():
    javaHome = os.getenv('JAVA_HOME')
    if javaHome is None:
        if exists('/usr/lib/java/java-6-sun'):
            javaHome = '/usr/lib/java/java-6-sun'
        elif exists('/System/Library/Frameworks/JavaVM.framework/Versions/1.6/Home'):
            javaHome = '/System/Library/Frameworks/JavaVM.framework/Versions/1.6/Home'
        elif exists('/usr/jdk/latest'):
            javaHome = '/usr/jdk/latest'
    return javaHome

def check_get_env(key):
    """
    Gets an environment variable, aborting with a useful message if it is not set.
    """
    value = os.environ.get(key)
    if value is None:
        abort('Required environment variable ' + key + ' must be set')
    return value

def log(msg=None):
    """
    Write a message to the console.
    All script output goes through this method thus allowing a subclass
    to redirect it. 
    """
    if msg is None:
        print
    else:
        print msg

def expand_project_in_class_path_arg(cpArg):
    cp = []
    for part in cpArg.split(os.pathsep):
        if part.startswith('@'):
            cp += classpath(part[1:]).split(os.pathsep)
        else:
            cp.append(part)
    return os.pathsep.join(cp)
    
def expand_project_in_args(args):
    for i in range(len(args)):
        if args[i] == '-cp' or args[i] == '-classpath':
            if i + 1 < len(args):
                args[i + 1] = expand_project_in_class_path_arg(args[i + 1])
            return


def gmake_cmd():
    for a in ['make', 'gmake', 'gnumake']:
        try:
            output = subprocess.check_output([a, '--version'])
            if 'GNU' in output:
                return a;
        except:
            pass
    abort('Could not find a GNU make executable on the current path.')

def expandvars_in_property(value):
        result = expandvars(value)
        if '$' in result or '%' in result:
            abort('Property contains an undefined environment variable: ' + value)
        return result

           
def abort(codeOrMessage):
    """
    Aborts the program with a SystemExit exception.
    If 'codeOrMessage' is a plain integer, it specifies the system exit status;
    if it is None, the exit status is zero; if it has another type (such as a string),
    the object's value is printed and the exit status is one.
    """
    raise SystemExit(codeOrMessage)

def download(path, urls, verbose=False):
    """
    Attempts to downloads content for each URL in a list, stopping after the first successful download.
    If the content cannot be retrieved from any URL, the program is aborted. The downloaded content
    is written to the file indicated by 'path'.
    """
    d = dirname(path)
    if d != '' and not exists(d):
        os.makedirs(d)
        
    def url_open(url):
        userAgent = 'Mozilla/5.0 (compatible)'
        headers = { 'User-Agent' : userAgent }
        req = urllib2.Request(url, headers=headers)
        return urllib2.urlopen(req);
        
    for url in urls:
        try:
            if (verbose):
                log('Downloading ' + url + ' to ' + path)
            if url.startswith('zip:') or url.startswith('jar:'):
                i = url.find('!/')
                if i == -1:
                    abort('Zip or jar URL does not contain "!/": ' + url)
                url, _, entry = url[len('zip:'):].partition('!/')
                with contextlib.closing(url_open(url)) as f:
                    data = f.read()
                    zipdata = StringIO.StringIO(f.read())
            
                zf = zipfile.ZipFile(zipdata, 'r')
                data = zf.read(entry)
                with open(path, 'w') as f:
                    f.write(data)
            else:
                with contextlib.closing(url_open(url)) as f:
                    data = f.read()
                with open(path, 'w') as f:
                    f.write(data)
            return
        except IOError as e:
            log('Error reading from ' + url + ': ' + str(e))
        except zipfile.BadZipfile as e:
            log('Error in zip file downloaded from ' + url + ': ' + str(e))
            
    # now try it with Java - urllib2 does not handle meta refreshes which are used by Sourceforge
    myDir = dirname(__file__)
    
    javaSource = join(myDir, 'URLConnectionDownload.java')
    javaClass = join(myDir, 'URLConnectionDownload.class')
    if not exists(javaClass) or getmtime(javaClass) < getmtime(javaSource):
        subprocess.check_call([_opts.javac, '-d', myDir, javaSource])
    if run([_opts.java, '-cp', myDir, 'URLConnectionDownload', path] + urls) != 0:
        abort('Could not download to ' + path + ' from any of the following URLs:\n\n    ' +
                  '\n    '.join(urls) + '\n\nPlease use a web browser to do the download manually')

def update_file(path, content):
    """
    Updates a file with some given content if the content differs from what's in
    the file already. The return value indicates if the file was updated.
    """
    existed = exists(path)
    try:
        old = None
        if existed:
            with open(path, 'rb') as f:
                old = f.read()
        
        if old == content:
            return False
            
        with open(path, 'wb') as f:
            f.write(content)
            
        log(('modified ' if existed else 'created ') + path)
        return True;
    except IOError as e:
        abort('Error while writing to ' + path + ': ' + str(e));

# Builtin commands
            
def build(args):
    """compile the Java and C sources, linking the latter

    Compile all the Java source code using the appropriate compilers
    and linkers for the various source code types."""
    
    parser = ArgumentParser(prog='mx build');
    parser.add_argument('-f', action='store_true', dest='force', help='force compilation even if class files are up to date')
    parser.add_argument('-c', action='store_true', dest='clean', help='removes existing build output')
    parser.add_argument('--no-native', action='store_false', dest='native', help='do not build com.oracle.max.vm.native')
    parser.add_argument('--jdt', help='Eclipse installation or path to ecj.jar for using the Eclipse batch compiler instead of javac', metavar='<path>')

    args = parser.parse_args(args)
    
    jdtJar = None
    if args.jdt is not None:
        if args.jdt.endswith('.jar'):
            jdtJar=args.jdt
        elif isdir(args.jdt):
            plugins = join(args.jdt, 'plugins')
            choices = [f for f in os.listdir(plugins) if fnmatch.fnmatch(f, 'org.eclipse.jdt.core_*.jar')]
            if len(choices) != 0:
                jdtJar = join(plugins, sorted(choices, reverse=True)[0])

    built = set()
    for p in sorted_deps():
        
        if p.native:
            log('Compiling C sources in {0}...'.format(p.dir))

            if args.clean:
                run([gmake_cmd(), 'clean'], cwd=p.dir)
                
            run([gmake_cmd()], cwd=p.dir)
            built.add(p.name)
            continue
        
        outputDir = p.output_dir()
        if exists(outputDir):
            if args.clean:
                log('Cleaning {0}...'.format(outputDir))
                shutil.rmtree(outputDir)
                os.mkdir(outputDir)
        else:
            os.mkdir(outputDir)

        cp = classpath(p.name)
        sourceDirs = p.source_dirs()
        mustBuild = args.force
        if not mustBuild:
            for dep in p.all_deps([], False):
                if dep.name in built:
                    mustBuild = True
            
        for sourceDir in sourceDirs:
            javafilelist = []
            nonjavafilelist = []
            for root, _, files in os.walk(sourceDir):
                javafiles = [join(root, name) for name in files if name.endswith('.java') and name != 'package-info.java']
                javafilelist += javafiles
                nonjavafilelist += [join(root, name) for name in files if not name.endswith('.java')]
                if not mustBuild:
                    for javafile in javafiles:
                        classfile = outputDir + javafile[len(sourceDir):-len('java')] + 'class'
                        if not exists(classfile) or os.path.getmtime(javafile) > os.path.getmtime(classfile):
                            mustBuild = True
                            break
                
            if not mustBuild:
                log('[all class files in {0} are up to date - skipping]'.format(sourceDir))
                continue
                
            if len(javafilelist) == 0:
                log('[no Java sources in {0} - skipping]'.format(sourceDir))
                continue

            built.add(p.name)

            argfileName = join(p.dir, 'javafilelist.txt')
            argfile = open(argfileName, 'w')
            argfile.write('\n'.join(javafilelist))
            argfile.close()
            
            try:
                if jdtJar is None:
                    log('Compiling Java sources in {0} with javac...'.format(sourceDir))
                    
                    class Filter:
                        """
                        Class to filter the 'is Sun proprietary API and may be removed in a future release'
                        warning when compiling the VM classes.
                        
                        """
                        def __init__(self):
                            self.c = 0
                        
                        def eat(self, line):
                            if 'Sun proprietary API' in line:
                                self.c = 2
                            elif self.c != 0:
                                self.c -= 1
                            else:
                                print line.rstrip()
                        
                    run([_opts.javac, '-g', '-J-Xmx1g', '-classpath', cp, '-d', outputDir, '@' + argfile.name], err=Filter().eat)
                else:
                    log('Compiling Java sources in {0} with JDT...'.format(sourceDir))
                    jdtProperties = join(p.dir, '.settings', 'org.eclipse.jdt.core.prefs')
                    if not exists(jdtProperties):
                        raise SystemError('JDT properties file {0} not found'.format(jdtProperties))
                    run([_opts.java, '-Xmx1g', '-jar', jdtJar, '-1.6', '-cp', cp, '-g',
                             '-properties', jdtProperties, 
                             '-warn:-unusedImport,-unchecked',
                             '-d', outputDir, '@' + argfile.name])
            finally:
                os.remove(argfileName)
                        
                
            for name in nonjavafilelist:
                dst = join(outputDir, name[len(sourceDir) + 1:])
                if exists(dirname(dst)):
                    shutil.copyfile(name, dst)

def canonicalizeprojects(args):
    """process all project files to canonicalize the dependencies

    The exit code of this command reflects how many files were updated."""
    
    changedFiles = 0
    for s in suites():
        projectsFile = join(s.dir, 'mx', 'projects')
        if not exists(projectsFile):
            continue
        with open(projectsFile) as f:
            out = StringIO.StringIO()
            pattern = re.compile('project@([^@]+)@dependencies=.*')
            for line in f:
                line = line.strip()
                m = pattern.match(line)
                if m is None:
                    out.write(line + '\n')
                else:
                    p = project(m.group(1))
                    out.write('project@' + m.group(1) + '@dependencies=' + ','.join(p.canonical_deps()) + '\n')
            content = out.getvalue()
        if update_file(projectsFile, content):
            changedFiles += 1
    return changedFiles;
    
def checkstyle(args):
    """run Checkstyle on the Java sources

   Run Checkstyle over the Java sources. Any errors or warnings
   produced by Checkstyle result in a non-zero exit code.

If no projects are given, then all Java projects are checked."""
    
    for p in sorted_deps():
        if p.native:
            continue
        sourceDirs = p.source_dirs()
        dotCheckstyle = join(p.dir, '.checkstyle')
        
        if not exists(dotCheckstyle):
            continue
        
        for sourceDir in sourceDirs:
            javafilelist = []
            for root, _, files in os.walk(sourceDir):
                javafilelist += [join(root, name) for name in files if name.endswith('.java') and name != 'package-info.java']
            if len(javafilelist) == 0:
                log('[no Java sources in {0} - skipping]'.format(sourceDir))
                continue

            timestampFile = join(p.suite.dir, 'mx', '.checkstyle' + sourceDir[len(p.suite.dir):].replace(os.sep, '_') + '.timestamp')
            mustCheck = False
            if exists(timestampFile):
                timestamp = os.path.getmtime(timestampFile)
                for f in javafilelist:
                    if os.path.getmtime(f) > timestamp:
                        mustCheck = True
                        break
            else:
                mustCheck = True
            
            if not mustCheck:
                log('[all Java sources in {0} already checked - skipping]'.format(sourceDir))
                continue

            if exists(timestampFile):                
                os.utime(timestampFile, None)
            else:
                file(timestampFile, 'a')
            
            dotCheckstyleXML = xml.dom.minidom.parse(dotCheckstyle)
            localCheckConfig = dotCheckstyleXML.getElementsByTagName('local-check-config')[0]
            configLocation = localCheckConfig.getAttribute('location')
            configType = localCheckConfig.getAttribute('type')
            if configType == 'project':
                # Eclipse plugin "Project Relative Configuration" format:
                #
                #  '/<project_name>/<suffix>'
                #
                if configLocation.startswith('/'):
                    name, _, suffix = configLocation.lstrip('/').partition('/')
                    config = join(project(name).dir, suffix)
                else:
                    config = join(p.dir, configLocation)
            else:
                log('[unknown Checkstyle configuration type "' + configType + '" in {0} - skipping]'.format(sourceDir))
                continue
                
            exclude = join(p.dir, '.checkstyle.exclude')
            
            if exists(exclude):
                with open(exclude) as f:
                    # Convert patterns to OS separators
                    patterns = [name.rstrip().replace('/', os.sep) for name in f.readlines()]
                def match(name):
                    for p in patterns:
                        if p in name:
                            log('excluding: ' + name)
                            return True
                    return False
                    
                javafilelist = [name for name in javafilelist if not match(name)]
            
            auditfileName = join(p.dir, 'checkstyleOutput.txt')
            log('Running Checkstyle on {0} using {1}...'.format(sourceDir, config))
            
            try:

                # Checkstyle is unable to read the filenames to process from a file, and the
                # CreateProcess function on Windows limits the length of a command line to
                # 32,768 characters (http://msdn.microsoft.com/en-us/library/ms682425%28VS.85%29.aspx)
                # so calling Checkstyle must be done in batches.
                while len(javafilelist) != 0:
                    i = 0
                    size = 0
                    while i < len(javafilelist):
                        s = len(javafilelist[i]) + 1
                        if (size + s < 30000):
                            size += s
                            i += 1
                        else:
                            break
                    
                    batch = javafilelist[:i]
                    javafilelist = javafilelist[i:]
                    try:
                        run_java(['-Xmx1g', '-jar', library('CHECKSTYLE').get_path(True), '-c', config, '-o', auditfileName] + batch)
                    finally:
                        if exists(auditfileName):
                            with open(auditfileName) as f:
                                warnings = [line.strip() for line in f if 'warning:' in line]
                                if len(warnings) != 0:
                                    map(log, warnings)
                                    return 1
            finally:
                if exists(auditfileName):
                    os.unlink(auditfileName)
    return 0

def clean(args):
    """remove all class files, images, and executables

    Removes all files created by a build, including Java class files, executables, and
    generated images.
    """
    
    for p in projects():
        if p.native:
            run([gmake_cmd(), '-C', p.dir, 'clean'])
        else:
            outputDir = p.output_dir()
            if outputDir != '' and exists(outputDir):
                log('Removing {0}...'.format(outputDir))
                shutil.rmtree(outputDir)
    
def help_(args):
    """show help for a given command

With no arguments, print a list of commands and short help for each command.

Given a command name, print help for that command."""
    if len(args) == 0:
        _argParser.print_help()
        return
    
    name = args[0]
    if not commands.has_key(name):
        _argParser.error('unknown command: ' + name)
    
    value = commands[name]
    (func, usage) = value[:2]
    doc = func.__doc__
    if len(value) > 2:
        docArgs = value[2:]
        fmtArgs = []
        for d in docArgs:
            if isinstance(d, Callable):
                fmtArgs += [d()]
            else:
                fmtArgs += [str(d)]
        doc = doc.format(*fmtArgs)
    print 'mx {0} {1}\n\n{2}\n'.format(name, usage, doc)


# Commands are in alphabetical order in this file.

def javap(args):
    """launch javap with a -classpath option denoting all available classes

    Run the JDK javap class file disassembler with the following prepended options:

        -private -verbose -classpath <path to project classes>"""
        
    javap = _opts.javap
    if not exists(javap):
        abort('The javap executable does not exists: ' + javap)
    else:
        run([javap, '-private', '-verbose', '-classpath', classpath()] + args)

def show_projects(args):
    """show all loaded projects"""
    for s in suites():
        projectsFile = join(s.dir, 'mx', 'projects')
        if exists(projectsFile):
            log(projectsFile)
            for p in s.projects:
                log('\t' + p.name)

def add_argument(*args, **kwargs):
    """
    Define how a single command-line argument.
    """
    assert _argParser is not None
    _argParser.add_argument(*args, **kwargs)
    
# Table of commands in alphabetical order.
# Keys are command names, value are lists: [<function>, <usage msg>, <format args to doc string of function>...]
# If any of the format args are instances of Callable, then they are called with an 'env' are before being
# used in the call to str.format().  
# Extensions should update this table directly
commands = {
    'build': [build, '[options] projects...'],
    'checkstyle': [checkstyle, 'projects...'],
    'canonicalizeprojects': [canonicalizeprojects, ''],
    'clean': [clean, ''],
    'help': [help_, '[command]'],
    'javap': [javap, ''],
    'projects': [show_projects, ''],
}

_argParser = ArgParser()

def main():    
    MX_INCLUDES = os.environ.get('MX_INCLUDES', None)
    if MX_INCLUDES is not None:
        for path in MX_INCLUDES.split(os.pathsep):
            d = join(path, 'mx')
            if exists(d) and isdir(d):
                _loadSuite(path)
                
    cwdMxDir = join(os.getcwd(), 'mx')
    if exists(cwdMxDir) and isdir(cwdMxDir):
        _loadSuite(os.getcwd(), True)
            
    opts, commandAndArgs = _argParser._parse_cmd_line()
    global _opts
    _opts = opts
    
    if len(commandAndArgs) == 0:
        _argParser.print_help()
        return
    
    command = commandAndArgs[0]
    command_args = commandAndArgs[1:]
    
    if not commands.has_key(command):
        abort('mx: unknown command \'{0}\'\n{1}use "mx help" for more options'.format(command, _format_commands()))
        
    c, _ = commands[command][:2]
    try:
        retcode = c(command_args)
        if retcode is not None and retcode != 0:
            abort(retcode)
    except KeyboardInterrupt:
        # no need to show the stack trace when the user presses CTRL-C
        abort(1)

if __name__ == '__main__':
    # rename this module as 'mx' so it is not imported twice by the commands.py modules
    sys.modules['mx'] = sys.modules.pop('__main__')
    
    main()