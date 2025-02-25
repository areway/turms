package integration.access;

import im.turms.gateway.access.common.function.ConnectionHandler;
import im.turms.gateway.access.tcp.factory.TcpServerFactory;
import im.turms.server.common.manager.ServerStatusManager;
import im.turms.server.common.property.env.gateway.TcpProperties;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.OngoingStubbing;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.DisposableServer;
import reactor.netty.tcp.TcpClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Log4j2
class TcpServerIT {

    private static final ConnectionHandler NEVER_CLOSE = (connection, isWebSocketConnection, in, out, onClose) -> Mono.never();

    @Test
    void shouldCloseOrAcceptConnection_accordingTo_ServerStatusManager_isActive() throws InterruptedException {
        TcpProperties tcpProperties = new TcpProperties();
        tcpProperties.getSsl().setEnabled(false);
        tcpProperties.setPort(0);
        tcpProperties.setWiretap(true);

        ServerStatusManager serverStatusManager = mock(ServerStatusManager.class);
        List<Boolean> isActiveReturnValues = List.of(true, false, true, false);
        OngoingStubbing<Boolean> isActiveStubbing = when(serverStatusManager.isActive());
        for (Boolean returnValue : isActiveReturnValues) {
            isActiveStubbing = isActiveStubbing.thenReturn(returnValue);
        }

        DisposableServer server = TcpServerFactory.create(tcpProperties, serverStatusManager, NEVER_CLOSE);

        int i = 0;
        for (Boolean isActive : isActiveReturnValues) {
            log.debug("The client with index {} is connecting...", i);
            Connection connection = TcpClient.create()
                    .host(server.host())
                    .port(server.port())
                    .handle((in, out) -> Mono.never())
                    .connectNow();
            // Must try to read data from the channel,
            // or the socket channel will reflect an outdated connection state.
            // It will throw "java.io.IOException: An existing connection was forcibly closed by the remote host"
            // if the connection is closed
            connection.inbound().receive().subscribe();
            // Wait for the server to close the connection
            Thread.sleep(200);
            boolean isConnected = !connection
                    .isDisposed();
            assertThat(isConnected)
                    .isEqualTo(isActive);
            i++;
        }
    }

}
