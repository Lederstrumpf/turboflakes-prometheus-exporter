{
  config,
  lib,
  ...
}:

with lib;

let
  cfg = config.services.turboflakes-monitor;

  validatorOpts = { ... }: {
    options = {
      address = mkOption {
        type = types.str;
        description = "Validator address to monitor";
      };

      port = mkOption {
        type = types.port;
        description = "Port for metrics endpoint";
      };

      network = mkOption {
        type = types.str;
        default = "polkadot";
        description = "Network to monitor (polkadot, kusama, etc.)";
      };

      apiEndpoint = mkOption {
        type = types.nullOr types.str;
        default = null;
        description = "API endpoint to scrape (overrides network setting)";
      };

      scrapeInterval = mkOption {
        type = types.int;
        default = 10;
        description = "Scrape interval in seconds";
      };
    };
  };

  buildApiEndpoint =
    validatorCfg:
    if validatorCfg.apiEndpoint != null then
      validatorCfg.apiEndpoint
    else
      "https://${validatorCfg.network}-onet-api.turboflakes.io/api/v1/validators/${validatorCfg.address}/grade";
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
      default = { };
      description = "Validators to monitor";
      example = literalExpression ''
        {
          validator1 = {
            address = "16A4n4UQqgxw5ndeehPjUAobDNmuX2bBoPXVKj4xTe16ktRN";
            port = 8090;
            network = "polkadot";
          };
          validator2 = {
            address = "xyz123...";
            port = 8091;
            network = "kusama";
          };
        }
      '';
    };
  };

  config = mkIf cfg.enable {
    systemd.services = mapAttrs' (
      name: validatorCfg:
      nameValuePair "turboflakes-monitor-${name}" {
        description = "TurboFlakes Monitor for ${name} on ${validatorCfg.network}";
        wantedBy = [ "multi-user.target" ];
        after = [ "network-online.target" ];
        wants = [ "network-online.target" ];

        serviceConfig = {
          Type = "simple";
          ExecStart = "${cfg.package}/bin/turboflakes-monitor --endpoint ${buildApiEndpoint validatorCfg} --port ${toString validatorCfg.port} --interval ${toString validatorCfg.scrapeInterval}";
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
