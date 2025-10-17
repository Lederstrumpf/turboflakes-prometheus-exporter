{ ... }:

{
  imports = [
    # If using the flake directly:
    # inputs.turboflakes-monitor.nixosModules.default

    # Or if using the standalone module:
    # ./module.nix
  ];

  services.turboflakes-monitor = {
    enable = true;

    validators = {
      polkadot-validator1 = {
        address = "16A4n4UQqgxw5ndeehPjUAobDNmuX2bBoPXVKj4xTe16ktRN";
        port = 8090;
        network = "polkadot";
      };

      polkadot-validator2 = {
        address = "AnotherPolkadotAddress123";
        port = 8091;
        network = "polkadot";
      };

      kusama-validator1 = {
        address = "KusamaValidatorAddress456";
        port = 8092;
        network = "kusama";
      };

      kusama-validator2 = {
        address = "AnotherKusamaAddress789";
        port = 8093;
        network = "kusama";
        scrapeInterval = 15;
      };

      custom-validator = {
        address = "CustomAddress";
        port = 8094;
        network = "polkadot";
        apiEndpoint = "https://custom-api.example.com/api/v1/validators/CustomAddress/grade";
      };
    };
  };

  networking.firewall.allowedTCPPorts = [
    8090
    8091
    8092
    8093
    8094
  ];
}
