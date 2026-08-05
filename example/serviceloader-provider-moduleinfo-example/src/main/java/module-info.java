import com.aicas.osgi.spi.example.provider.SPIProviderImpl2;

module provider.moduleinfo
{
  requires serviceloader.spi;

  provides com.aicas.osgi.spi.example.spi.SPIProvider
      with SPIProviderImpl2;
}
