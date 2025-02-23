/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
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

package im.turms.server.common.exception;

import im.turms.server.common.cluster.service.rpc.exception.RpcException;
import im.turms.server.common.constant.TurmsStatusCode;
import im.turms.server.common.mongo.exception.DuplicateKeyException;
import lombok.Data;

@Data
public final class ThrowableInfo {

    private final TurmsStatusCode code;
    private final String reason;

    private ThrowableInfo(Throwable throwable) {
        if (throwable instanceof TurmsBusinessException e) {
            code = e.getCode();
            reason = e.getReason();
        } else if (throwable instanceof RpcException e) {
            code = e.getStatusCode();
            reason = e.getMessage();
        } else if (throwable instanceof DuplicateKeyException e) {
            // We consider DuplicateKeyException as a client error here,
            // because if it's indeed an exception caused by the illegal args provided
            // by the server, it should recover in the upstream rather than
            // passing down DuplicateKeyException
            code = TurmsStatusCode.RECORD_CONTAINS_DUPLICATE_KEY;
            reason = e.getMessage();
        } else {
            code = TurmsStatusCode.SERVER_INTERNAL_ERROR;
            reason = throwable.getMessage();
        }
    }

    public static ThrowableInfo get(Throwable throwable) {
        return new ThrowableInfo(throwable);
    }

}