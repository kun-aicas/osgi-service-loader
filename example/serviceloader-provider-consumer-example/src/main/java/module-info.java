import com.aicas.osgi.spi.example.provider.SPIProviderImpl3;

module consumer.provider
{
  requires serviceloader.spi;

  uses com.aicas.osgi.spi.example.spi.SPIProvider;

  provides com.aicas.osgi.spi.example.spi.SPIProvider
  with SPIProviderImpl3;
}
