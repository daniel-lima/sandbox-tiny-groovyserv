/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @author Daniel Henrique Alves Lima
 */
import java.io.IOException;

import groovy.lang.GroovyClassLoader
import org.codehaus.groovy.runtime.StackTraceUtils

import org.apache.sshd.SshServer
import org.apache.sshd.server.auth.UserAuthNone
import org.apache.sshd.server.Command
import org.apache.sshd.server.CommandFactory
import org.apache.sshd.server.command.ScpCommandFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider

//import org.apache.sshd.server.PasswordAuthenticator
//import org.apache.sshd.server.PublickeyAuthenticator

SshServer sshd = SshServer.setUpDefaultServer()
sshd.port = args && args.length > 0? args[0] as int : 8123
sshd.keyPairProvider = new SimpleGeneratorHostKeyProvider('hostkey.ser')

def sysOut = System.out

def findMyClassLoader = {ClassLoader cl = Thread.currentThread().contextClassLoader ->
    while (cl != null && !(cl instanceof MyGroovyClassLoader)) {
        cl = cl.parent
    }
    return cl
}

sshd.commandFactory = new ScpCommandFactory(
    {command ->

        command = command.tokenize(' ')
        def cmd
        cmd = [
            input: null, output: null, error:null, callback: null,
            setExitCallback: {cb -> cmd.callback = cb},
            setInputStream: {is -> cmd.input = is as InputStream},
            setOutputStream: {out -> cmd.output = new PrintStream(out)},
            setErrorStream: {err -> cmd.error = new PrintStream(err)},
            destroy: {},
            start: {env ->
                Thread currentThread = Thread.currentThread()
                sysOut.println "${currentThread} begin"
                def oldThreadCl = currentThread.contextClassLoader
                def cl = new MyGroovyClassLoader(oldThreadCl? oldThreadCl : this.class.classLoader)
                cl.errorStream = cmd.error; cl.inputStream = cmd.input; cl.outputStream = cmd.output
				
                def scriptName = command.size() > 0?"${command[0]}":''
                def scriptArgs = (command.size() > 1? command.subList(1, command.size()): []) as String[]
                ThreadGroup tg = new ThreadGroup(currentThread.threadGroup, 
                                                 "${currentThread.threadGroup.name}:${scriptName}")
                Thread t = new Thread(tg,
                                      {
                                          long time = -1; int result = -1
                                          try {							
                                              def scriptFile = new File(scriptName)
                                              if (scriptFile.parentFile) {cl.addURL(scriptFile.parentFile.toURI().toURL())}
                                              def script = cl.parseClass(scriptFile)
                                              assert cl.equals(findMyClassLoader(script.classLoader))
                                              time = System.currentTimeMillis()
                                              script = script.newInstance()
                                              script.args = scriptArgs; script.run()
                                              result = 0
                                          } catch (Exception e) {
                                              e =  StackTraceUtils.deepSanitize(e)
                                              sshd.log.error("Error running script ${scriptName}", e)
                                              e.printStackTrace()
                                              throw e
                                          } finally {
                                              try {
                                                  while (tg.activeCount() > 1 || tg.activeGroupCount() > 1) {Thread.sleep 100}
                                                  System.err.flush(); System.out.flush()
                                                  cl.release()
                                              } finally {
                                                  if (time > 0) {time = System.currentTimeMillis() - time; println "${Thread.currentThread()} ${time}(ms)"}
                                                  sysOut.println "${Thread.currentThread()} end"
                                                  cmd.callback.onExit(result)
                                              }
                                          }
                                      }
                                      as Runnable
                                     )
                t.contextClassLoader = cl
                t.start()	
            }
        ]
        
        return cmd as Command

    } as CommandFactory
)

/*sshd.publickeyAuthenticator = {username, key, session ->
    return true
    } as PublickeyAuthenticator*/


/*sshd.passwordAuthenticator = {username, password, session ->
    return true
    } as PasswordAuthenticator*/


def userAuthFactories = sshd.userAuthFactories
if (!userAuthFactories) {userAuthFactories = []}
userAuthFactories << new UserAuthNone.Factory()
sshd.userAuthFactories = userAuthFactories


System.err = new PrintStream(new MyOutputStream(System.err, findMyClassLoader))
System.out = new PrintStream(new MyOutputStream(System.out, findMyClassLoader))
System.in = new MyInputStream(System.in, findMyClassLoader)

sshd.start()


class MyGroovyClassLoader extends GroovyClassLoader {

    def inputStream
    def outputStream
    def errorStream
    
    public MyGroovyClassLoader() {
        super()
    }

    public MyGroovyClassLoader(ClassLoader loader) {
        super(loader)
    }

    public MyGroovyClassLoader(GroovyClassLoader parent) {
        super(parent)
    }
    
    public void release() {
        errorStream?.flush(); outputStream?.flush()
        errorStream?.close(); outputStream?.close(); inputStream?.close()
    }
    
}

class MyInputStream extends InputStream {
    
    private InputStream input
    private Closure selectCl
    
    public MyInputStream(InputStream input, Closure selectCl) {this.input = input; this.selectCl = selectCl}

    @Override
    public int read() throws IOException {
        def cl = selectCl()
        return (cl? cl.inputStream : input).read()
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        def cl = selectCl()
        return (cl? cl.inputStream : input).read(b, off, len)
    }
          
}

class MyOutputStream extends OutputStream {
    
    private OutputStream output
    private Closure selectCl
    
    public MyOutputStream(OutputStream output, Closure selectCl) {this.output = output; this.selectCl = selectCl}

    @Override
    public void write(int b) throws IOException {
        def cl = selectCl()
        (cl? cl.outputStream : output).write(b)
    }
    
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        def cl = selectCl()
        (cl? cl.outputStream : output).write(b, off, len)
        flush() //
    }

    @Override
    public void flush() throws IOException {
        def cl = selectCl()
        (cl? cl.outputStream : output).flush()
    }
    
}