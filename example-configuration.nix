{ config, pkgs, ... }:

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
      validator1 = {
        address = "16A4n4UQqgxw5ndeehPjUAobDNmuX2bBoPXVKj4xTe16ktRN";
        port = 8090;
      };
      
      validator2 = {
        address = "AnotherValidatorAddress123";
        port = 8091;
      };
      
      validator3 = {
        address = "YetAnotherValidator456";
        port = 8092;
        scrapeInterval = 15;  # Override default
      };
      
      # Add up to 12 validators...
    };
  };
  
  # Optional: Open firewall ports for Prometheus scraping
  networking.firewall.allowedTCPPorts = [ 8090 8091 8092 ];
}
