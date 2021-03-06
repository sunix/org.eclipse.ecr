/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Nuxeo - initial API and implementation
 *
 * $Id: JOOoConvertPluginImpl.java 18651 2007-05-13 20:28:53Z sfermigier $
 */

package org.eclipse.ecr.core.io;

import java.io.IOException;
import java.io.OutputStream;

import org.eclipse.ecr.core.api.ClientException;
import org.eclipse.ecr.core.io.exceptions.ExportDocumentException;

/**
 * Simple interface useful to wrap a sequence of calls for performing an export.
 * This could be handy to quickly define an exporter and sent it as parameter so
 * the method will be callback.
 *
 * @author <a href="mailto:dm@nuxeo.com">Dragos Mihalache</a>
 */
public interface DocumentsExporter {

    DocumentTranslationMap exportDocs(OutputStream out)
            throws ExportDocumentException, ClientException, IOException;

}
