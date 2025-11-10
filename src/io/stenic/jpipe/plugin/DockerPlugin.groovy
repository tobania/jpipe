package io.stenic.jpipe.plugin

import io.stenic.jpipe.event.Event

class DockerPlugin extends Plugin {

    private String depCredentialId
    private String depServer
    private String credentialId
    private String server
    private String repository
    private String buildArgs
    private String target
    private Boolean push
    private String filePath
    private String testScript
    private List extraTargets
    private List extraTags
    private Boolean useCache
    private Boolean doCleanup
    private String buildArgVersionKey

    DockerPlugin(Map opts = [:]) {
        this.repository = opts.get('repository', '')
        this.credentialId = opts.get('credentialId', '')
        this.server = opts.get('server', 'http://index.docker.io')
        this.depCredentialId = opts.get('depCredentialId', this.credentialId)
        this.depServer = opts.get('depServer', this.server) 
        this.buildArgs = opts.get('buildArgs', '')
        this.push = opts.get('push', this.credentialId != '')
        this.filePath = opts.get('filePath', '.')
        this.target = opts.get('target', '')
        this.extraTargets = opts.get('extraTargets', [])
        this.extraTags = opts.get('extraTags', [])
        this.testScript = opts.get('testScript', '')
        this.useCache = opts.get('useCache', true)
        this.doCleanup = opts.get('doCleanup', false)
        this.buildArgVersionKey = opts.get('buildArgVersionKey', 'VERSION')
    }

    public Map getSubscribedEvents() {
        return [
            "${Event.BUILD}": [
                [{ event -> this.doDockerBuild(event) }, 0],
            ],
            "${Event.TEST}": [
                [{ event -> this.doTest(event) }, 0],
            ],
            "${Event.PUBLISH}": [
                [{ event -> this.doDockerPush(event) }, -10],
                [{ event -> this.doDockerCleanup(event) }, 100],
            ],
        ]
    }

    public void doDockerBuild(Event event) { 
        event.script.docker.withRegistry(this.depServer, this.depCredentialId) {
             event.script.sshagent(credentials: [event.script.scm.getUserRemoteConfigs()[0].getCredentialsId()], ignoreMissing: true) {
                 def depRegistry = this.depServer - ~/https:\/\//
                 event.script.sh "sed -r -n 's;.*FROM\\s+(${depRegistry}/.*)\\s+AS.*;\\1;p' Dockerfile > .images_to_pull"
                 def imagesToPull = script.readFile('.images_to_pull').trim().split( '\n' )
                 event.script.sh "rm -rf .images_to_pull"
                 imagesToPull.each { image ->
                     event.script.docker.image("${image}").pull()
                 }
             }
        }
        event.script.docker.withRegistry(this.server, this.credentialId) {
            if (this.useCache) {
                try {
                    event.script.docker.image("${this.repository}:cache").pull()
                    this.extraTargets.each { target ->
                        event.script.docker.image("${this.repository}:cache-${target}").pull()
                    }
                } catch (Exception e) { }
                }

            def buildArgs = this.buildArgs
            if (this.target != '') {
                buildArgs = "--target=${this.target} ${this.buildArgs}"
            }

            event.script.withEnv([
                'DOCKER_BUILDKIT=1'
            ]) {
                event.script.sshagent(credentials: [event.script.scm.getUserRemoteConfigs()[0].getCredentialsId()], ignoreMissing: true) {
                    event.script.docker.build(
                        "${this.repository}:${event.version}",
                        "${buildArgs} --build-arg ${this.buildArgVersionKey}=${event.version} ${this.filePath}"
                    )
                    this.extraTags.each { tag ->
                        event.script.sh "docker tag ${this.repository}:${event.version} ${this.repository}:${tag}"
                    }
                    this.extraTargets.each { target ->
                        event.script.docker.build(
                            "${this.repository}:${target}",
                            "--target=${target} ${this.buildArgs} --build-arg ${this.buildArgVersionKey}=${event.version} ${this.filePath}"
                        )
                    }
                }
            }
            }
        }

    public void doTest(Event event) {
        if (this.testScript != '') {
            event.script.docker.image("${this.repository}:${event.version}").inside {
                event.script.sh this.testScript
            }
        }
    }

    public void doDockerPush(Event event) {
        if (this.push) {
            event.script.docker.withRegistry(this.server, this.credentialId) {
                event.script.docker.image("${this.repository}:${event.version}").push()
                this.extraTags.each { tag ->
                    event.script.docker.image("${this.repository}:${tag}").push()
                }
                if (this.useCache) {
                    try {
                        event.script.docker.image("${this.repository}:${event.version}").push('cache')
                        this.extraTargets.each { target ->
                            event.script.docker.image("${this.repository}:${target}").push("cache-${target}")
                        }
                    } catch (Exception e) { }
                    }
                }
            }
        }

    public void doDockerCleanup(Event event) {
        if (!this.doCleanup) {
            return
        }
        try {
            event.script.sh "docker rmi ${this.repository}:${event.version}"
            this.extraTags.each { tag ->
                event.script.sh "docker rmi ${this.repository}:${tag}"
            }
            this.extraTargets.each { target ->
                event.script.sh "docker rmi ${this.repository}:${target}"
            }
            if (this.useCache) {
                event.script.sh "docker rmi ${this.repository}:cache"
                this.extraTargets.each { target ->
                    event.script.sh "docker rmi ${this.repository}:cache-${target}"
                }
            }
            event.script.sh 'docker rmi -f $(docker images -f "dangling=true" -q)'
        } catch (Exception e) { }
        }

    }
