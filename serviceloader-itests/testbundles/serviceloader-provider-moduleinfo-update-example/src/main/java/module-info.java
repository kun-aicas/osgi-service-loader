import com.aicas.osgi.spi.example.provider.SPIProviderImplModuleInfoUpdate;

module provider.moduleinfo
{
  requires serviceloader.spi;

  provides com.aicas.osgi.spi.example.spi.SPIProvider
      with SPIProviderImplModuleInfoUpdate;
}
