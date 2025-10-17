{ config, lib, pkgs, ... }:

with lib;

let
  cfg = config.services.turboflakes-monitor;
  
  validatorOpts = { name, ... }: {
    options = {
      address = mkOption {
        type = types.str;
        description = "Validator address to monitor";
      };
      
      port = mkOption {
        type = types.port;
        description = "Port for metrics endpoint";
      };
      
      apiEndpoint = mkOption {
        type = types.str;
        default = "https://polkadot-onet-api.turboflakes.io/api/v1/validators/${name}/grade";
        description = "API endpoint to scrape";
      };
      
      scrapeInterval = mkOption {
        type = types.int;
        default = 10;
        description = "Scrape interval in seconds";
      };
    };
  };
in
{
  options.services.turboflakes-monitor = {
    enable = mkEnableOption "TurboFlakes validator monitor";
    
    package = mkOption {
      type = types.package;
      description = "TurboFlakes monitor package to use";
    };
    
    validators = mkOption {
      type = types.attrsOf (types.submodule validatorOpts);
      default = {};
      description = "Validators to monitor";
      example = literalExpression ''
        {
          validator1 = {
            address = "16A4n4UQqgxw5ndeehPjUAobDNmuX2bBoPXVKj4xTe16ktRN";
            port = 8090;
          };
          validator2 = {
            address = "xyz123...";
            port = 8091;
          };
        }
      '';
    };
  };
  
  config = mkIf cfg.enable {
    systemd.services = mapAttrs' (name: validatorCfg:
      nameValuePair "turboflakes-monitor-${name}" {
        description = "TurboFlakes Monitor for ${name}";
        wantedBy = [ "multi-user.target" ];
        after = [ "network-online.target" ];
        wants = [ "network-online.target" ];
        
        environment = {
          API_ENDPOINT = validatorCfg.apiEndpoint;
          METRICS_PORT = toString validatorCfg.port;
          SCRAPE_INTERVAL = toString validatorCfg.scrapeInterval;
        };
        
        serviceConfig = {
          Type = "simple";
          ExecStart = "${cfg.package}/bin/turboflakes-monitor";
          Restart = "always";
          RestartSec = "10s";
          
          # Hardening
          DynamicUser = true;
          NoNewPrivileges = true;
          PrivateTmp = true;
          ProtectSystem = "strict";
          ProtectHome = true;
          ProtectKernelTunables = true;
          ProtectKernelModules = true;
          ProtectControlGroups = true;
        };
      }
    ) cfg.validators;
  };
}
