/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.gradle.internal.test;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.elasticsearch.gradle.LoggedExec;
import org.elasticsearch.gradle.internal.test.AntFixture;
import org.elasticsearch.gradle.internal.FixtureStop;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.Internal;

import javax.inject.Inject;
import java.io.IOException;

class AntFixtureStop extends LoggedExec implements FixtureStop {

    @Internal
    AntFixture fixture;

    @Internal
    FileSystemOperations fileSystemOperations;

    @Inject
    AntFixtureStop(FileSystemOperations fileSystemOperations) {
        super(fileSystemOperations);
        this.fileSystemOperations = fileSystemOperations;
    }

    final void setFixture(AntFixture fixture) {
        assert this.fixture == null;
        this.fixture = fixture;
        onlyIf( task -> fixture.getPidFile().exists() );
        String pid;
        try {
            pid = String.valueOf(fixture.getPid());
        } catch (IOException e) {
            getLogger().error("error getting pid from pidfile", e);
            throw new RuntimeException(e);
        }
        doFirst(
            task -> getLogger().info("Shutting down ${fixture.name} with pid ${pid}")
        );
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            setExecutable("Taskkill");
            args("/PID", pid, "/F");
        } else {
            setExecutable("kill");
            args("-9", pid);
        }
        doLast(t -> fileSystemOperations.delete(ops -> ops.delete(fixture.getPidFile()) ));
        this.fixture = fixture;
    }
}
