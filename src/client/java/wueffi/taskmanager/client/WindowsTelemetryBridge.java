package wueffi.taskmanager.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import wueffi.taskmanager.client.util.ConfigManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class WindowsTelemetryBridge {

    record Sample(
            long capturedAtEpochMillis,
            boolean bridgeActive,
            String counterSource,
            String sensorSource,
            String sensorErrorCode,
            String cpuTemperatureProvider,
            String gpuTemperatureProvider,
            String gpuHotSpotTemperatureProvider,
            double cpuCoreLoadPercent,
            double gpuCoreLoadPercent,
            double gpuTemperatureC,
            double gpuHotSpotTemperatureC,
            double cpuTemperatureC,
            long bytesReceivedPerSecond,
            long bytesSentPerSecond,
            long diskReadBytesPerSecond,
            long diskWriteBytesPerSecond,
            long sampleDurationMillis
    ) {
        static Sample empty() {
            return new Sample(0L, false, "Unavailable", "Unavailable", "No bridge data", "Unavailable", "Unavailable", "Unavailable", -1, -1, -1, -1, -1, -1, -1, -1, -1, 0L);
        }
    }

    record Health(String helperStatus, long latestSampleAgeMillis, long helperUptimeMillis, boolean fallbackActive, long latestSampleDurationMillis) {}

    private static final String SCRIPT = """
            $ErrorActionPreference = 'SilentlyContinue'
            $sampleStartedAt = [System.Environment]::TickCount64
            $netRecv = 0.0
            $netSent = 0.0
            $diskRead = 0.0
            $diskWrite = 0.0
            $cpuLoad = -1.0
            $gpuLoad = -1.0
            $cpuTemp = -1.0
            $gpuTemp = -1.0
            $gpuHotSpot = -1.0
            $cpuSensorSource = 'Unavailable'
            $gpuTempSensorSource = 'Unavailable'
            $gpuHotSpotSensorSource = 'Unavailable'
            $cpuSensorMatch = 'none'
            $gpuTempSensorMatch = 'none'
            $gpuHotSpotSensorMatch = 'none'
            $sensorAttempts = New-Object 'System.Collections.Generic.List[string]'
            $sensorErrors = New-Object 'System.Collections.Generic.List[string]'
            $counterSource = 'Windows Performance Counters'
            $activeRenderer = if ($env:TM_ACTIVE_RENDERER) { [string]$env:TM_ACTIVE_RENDERER } else { '' }
            $activeVendor = if ($env:TM_ACTIVE_VENDOR) { [string]$env:TM_ACTIVE_VENDOR } else { '' }
            $gpuPreferredMatchFound = $false
            $loadedAssemblyPaths = New-Object 'System.Collections.Generic.HashSet[string]'

            function Normalize-Name($text) {
              if ($null -eq $text) { return '' }
              return ([regex]::Replace(([string]$text).ToLowerInvariant(), '[^a-z0-9]+', ' ')).Trim()
            }

            function Test-PreferredGpu($hardwareName, $sensorName, $sensorIdentifier) {
              $target = Normalize-Name($script:activeRenderer + ' ' + $script:activeVendor)
              $candidate = Normalize-Name($hardwareName + ' ' + $sensorName + ' ' + $sensorIdentifier)
              if ([string]::IsNullOrWhiteSpace($target) -or [string]::IsNullOrWhiteSpace($candidate)) { return $false }
              if ($target.Contains($candidate) -or $candidate.Contains($target)) { return $true }
              $targetTokens = $target.Split(' ') | Where-Object { $_.Length -ge 3 }
              $hits = @($targetTokens | Where-Object { $candidate.Contains($_) }).Count
              return $hits -ge 2
            }


            function Add-SensorAttempt($text) {
              try { $script:sensorAttempts.Add($text) | Out-Null } catch {}
            }

            function Add-SensorError($provider, $err) {
              try {
                $message = if ([string]::IsNullOrWhiteSpace($err)) { 'Unknown error' } else { $err }
                $script:sensorErrors.Add(($provider + ': ' + $message)) | Out-Null
              } catch {}
            }

            function Ensure-LoadedAssembly($dllPath) {
              if ([string]::IsNullOrWhiteSpace($dllPath) -or -not (Test-Path $dllPath)) { return $false }
              try {
                if (-not $script:loadedAssemblyPaths.Contains($dllPath)) {
                  [System.Reflection.Assembly]::LoadFrom($dllPath) | Out-Null
                  $script:loadedAssemblyPaths.Add($dllPath) | Out-Null
                }
                return $true
              } catch {
                Add-SensorError(('DLL ' + [System.IO.Path]::GetFileName($dllPath)), $_.Exception.Message)
                return $false
              }
            }

            function Update-HardwareTree($hardware) {
              try { $hardware.Update() } catch {}
              try { foreach ($sub in $hardware.SubHardware) { Update-HardwareTree $sub } } catch {}
            }

            function Set-TemperatureFromSensor($origin, $hardwareType, $hardwareName, $sensorName, $sensorIdentifier, $sensorValue) {
              if ($sensorValue -le 0) { return }
              $search = ($hardwareType + ' ' + $hardwareName + ' ' + $sensorName + ' ' + $sensorIdentifier).ToLowerInvariant()
              $cpuMatch = ($search -match 'cpu package|cpu package temperature|package id|package|tctl|tdie|ccd|die|cpu core|core max|core average|processor|socket|ryzen|intel') -or (($hardwareType + ' ' + $hardwareName).ToLowerInvariant() -match 'cpu|processor' -and $sensorName -match 'temperature|tdie|tctl|package|cpu') -or ($sensorName -match '^cpu$')
              $gpuMatch = ($search -match 'gpu temperature|gpu core|hot spot|hotspot|junction|edge|graphics|mem junction|radeon|nvidia|intel graphics') -or (($hardwareType + ' ' + $hardwareName).ToLowerInvariant() -match 'gpu|graphics|radeon|nvidia|intel' -and $sensorName -match 'temperature|edge|junction|hot')
              if (($cpuTemp -lt 0 -or $sensorValue -gt $cpuTemp) -and $cpuMatch) {
                $script:cpuTemp = [double]$sensorValue
                $script:cpuSensorSource = $origin
                $script:cpuSensorMatch = $hardwareName + ' / ' + $sensorName
              }
              if ($gpuMatch) {
                $hotSpotMatch = $search -match 'hot spot|hotspot|junction temperature|junction|gpu junction|hot spot temperature|mem junction|memory junction|hot point'
                $preferredGpu = Test-PreferredGpu $hardwareName $sensorName $sensorIdentifier
                if ($preferredGpu) {
                  if ($hotSpotMatch) {
                      if ($script:gpuHotSpot -lt 0 -or $sensorValue -gt $script:gpuHotSpot) {
                        $script:gpuHotSpot = [double]$sensorValue
                        $script:gpuHotSpotSensorSource = $origin
                        $script:gpuHotSpotSensorMatch = $hardwareName + ' / ' + $sensorName
                      }
                    } elseif (-not $script:gpuPreferredMatchFound -or $gpuTemp -lt 0 -or $sensorValue -gt $gpuTemp) {
                      $script:gpuTemp = [double]$sensorValue
                    $script:gpuTempSensorSource = $origin
                    $script:gpuTempSensorMatch = $hardwareName + ' / ' + $sensorName
                  }
                  $script:gpuPreferredMatchFound = $true
                } elseif (-not $script:gpuPreferredMatchFound -and ($gpuTemp -lt 0 -or $sensorValue -gt $gpuTemp)) {
                  if ($hotSpotMatch) {
                    if ($script:gpuHotSpot -lt 0 -or $sensorValue -gt $script:gpuHotSpot) {
                      $script:gpuHotSpot = [double]$sensorValue
                      $script:gpuHotSpotSensorSource = $origin
                      $script:gpuHotSpotSensorMatch = $hardwareName + ' / ' + $sensorName
                    }
                  } else {
                    $script:gpuTemp = [double]$sensorValue
                    $script:gpuTempSensorSource = $origin
                    $script:gpuTempSensorMatch = $hardwareName + ' / ' + $sensorName
                  }
                }
              }
            }

            try {
              $cpuSample = Get-Counter '\\Processor Information(_Total)\\% Processor Utility','\\Processor(_Total)\\% Processor Time'
              foreach ($counter in $cpuSample.CounterSamples) {
                $path = $counter.Path.ToLowerInvariant()
                $value = [double]$counter.CookedValue
                if ($path -like '*processor information(_total)*% processor utility*') { if ($value -ge 0) { $cpuLoad = [math]::Max($cpuLoad, $value) } }
                elseif ($path -like '*processor(_total)*% processor time*' -and $cpuLoad -lt 0) { if ($value -ge 0) { $cpuLoad = [math]::Max($cpuLoad, $value) } }
              }
            } catch { Add-SensorError('CPU Counters', $_.Exception.Message) }

            try {
              $netSample = Get-Counter '\\Network Interface(*)\\Bytes Received/sec','\\Network Interface(*)\\Bytes Sent/sec'
              foreach ($counter in $netSample.CounterSamples) {
                $path = $counter.Path.ToLowerInvariant()
                $value = [double]$counter.CookedValue
                if ($path -like '*network interface(*)*bytes received/sec*' -or $path -like '*network interface(*\\)*bytes received/sec*') { if ($value -gt 0) { $netRecv += $value } }
                elseif ($path -like '*network interface(*)*bytes sent/sec*' -or $path -like '*network interface(*\\)*bytes sent/sec*') { if ($value -gt 0) { $netSent += $value } }
              }
            } catch { Add-SensorError('Network Counters', $_.Exception.Message) }

            try {
              $diskSample = Get-Counter '\\PhysicalDisk(_Total)\\Disk Read Bytes/sec','\\PhysicalDisk(_Total)\\Disk Write Bytes/sec'
              foreach ($counter in $diskSample.CounterSamples) {
                $path = $counter.Path.ToLowerInvariant()
                $value = [double]$counter.CookedValue
                if ($path -like '*physicaldisk(_total)*disk read bytes/sec*') { if ($value -gt 0) { $diskRead += $value } }
                elseif ($path -like '*physicaldisk(_total)*disk write bytes/sec*') { if ($value -gt 0) { $diskWrite += $value } }
              }
            } catch { Add-SensorError('Disk Counters', $_.Exception.Message) }

            try {
              $gpuCounterSample = Get-Counter '\\GPU Engine(*)\\Utilization Percentage'
              foreach ($counter in $gpuCounterSample.CounterSamples) {
                $path = $counter.Path.ToLowerInvariant()
                $value = [double]$counter.CookedValue
                if ($path -like '*gpu engine(*engtype_3d)*utilization percentage*' -or $path -like '*gpu engine(*engtype_compute_0)*utilization percentage*') {
                  if ($value -gt $gpuLoad) { $gpuLoad = $value }
                }
              }
            } catch { Add-SensorError('GPU Counters', $_.Exception.Message) }

            $dllCandidates = @(
              'C:\\Program Files\\LibreHardwareMonitor\\LibreHardwareMonitorLib.dll',
              'C:\\Program Files\\LibreHardwareMonitor\\LibreHardwareMonitor\\LibreHardwareMonitorLib.dll',
              'C:\\Program Files\\LibreHardwareMonitor\\Lib\\LibreHardwareMonitorLib.dll',
              'C:\\Program Files (x86)\\LibreHardwareMonitor\\LibreHardwareMonitorLib.dll',
              'C:\\Program Files (x86)\\LibreHardwareMonitor\\LibreHardwareMonitor\\LibreHardwareMonitorLib.dll',
              'C:\\Program Files (x86)\\LibreHardwareMonitor\\Lib\\LibreHardwareMonitorLib.dll',
              'C:\\Program Files\\OpenHardwareMonitor\\OpenHardwareMonitorLib.dll',
              'C:\\Program Files\\OpenHardwareMonitor\\OpenHardwareMonitor\\OpenHardwareMonitorLib.dll',
              'C:\\Program Files (x86)\\OpenHardwareMonitor\\OpenHardwareMonitorLib.dll',
              'C:\\Program Files (x86)\\OpenHardwareMonitor\\OpenHardwareMonitor\\OpenHardwareMonitorLib.dll'
            ) | Select-Object -Unique

            foreach ($dllPath in $dllCandidates) {
              try {
                if (-not (Test-Path $dllPath)) { continue }
                Add-SensorAttempt ('DLL ' + [System.IO.Path]::GetFileName($dllPath))
                if (-not (Ensure-LoadedAssembly $dllPath)) { continue }
                if ($dllPath -like '*LibreHardwareMonitor*') {
                  $computer = New-Object LibreHardwareMonitor.Hardware.Computer
                  $origin = 'LibreHardwareMonitor DLL'
                } else {
                  $computer = New-Object OpenHardwareMonitor.Hardware.Computer
                  $origin = 'OpenHardwareMonitor DLL'
                }
                if ($computer.PSObject.Properties.Match('IsCpuEnabled').Count -gt 0) { $computer.IsCpuEnabled = $true }
                if ($computer.PSObject.Properties.Match('CPUEnabled').Count -gt 0) { $computer.CPUEnabled = $true }
                if ($computer.PSObject.Properties.Match('IsGpuEnabled').Count -gt 0) { $computer.IsGpuEnabled = $true }
                if ($computer.PSObject.Properties.Match('GPUEnabled').Count -gt 0) { $computer.GPUEnabled = $true }
                if ($computer.PSObject.Properties.Match('IsMotherboardEnabled').Count -gt 0) { $computer.IsMotherboardEnabled = $true }
                if ($computer.PSObject.Properties.Match('MainboardEnabled').Count -gt 0) { $computer.MainboardEnabled = $true }
                if ($computer.PSObject.Properties.Match('IsControllerEnabled').Count -gt 0) { $computer.IsControllerEnabled = $true }
                $computer.Open()
                foreach ($hardware in $computer.Hardware) {
                  Update-HardwareTree $hardware
                  foreach ($sensor in $hardware.Sensors) {
                    if ([string]$sensor.SensorType -ne 'Temperature') { continue }
                    Set-TemperatureFromSensor $origin ([string]$hardware.HardwareType) ([string]$hardware.Name) ([string]$sensor.Name) ([string]$sensor.Identifier) ([double]$sensor.Value)
                  }
                  foreach ($sub in $hardware.SubHardware) {
                    try {
                      foreach ($sensor in $sub.Sensors) {
                        if ([string]$sensor.SensorType -ne 'Temperature') { continue }
                        Set-TemperatureFromSensor $origin ([string]$sub.HardwareType) ([string]$sub.Name) ([string]$sensor.Name) ([string]$sensor.Identifier) ([double]$sensor.Value)
                      }
                    } catch {}
                  }
                }
                try { $computer.Close() } catch {}
                if ($cpuTemp -ge 0 -and $gpuTemp -ge 0) { break }
              } catch { Add-SensorAttempt ('DLL failed ' + [System.IO.Path]::GetFileName($dllPath)); Add-SensorError(('DLL ' + [System.IO.Path]::GetFileName($dllPath)), $_.Exception.Message) }
            }

            $sensorNamespaces = @('root/LibreHardwareMonitor','root/OpenHardwareMonitor')
            foreach ($ns in $sensorNamespaces) {
              try {
                Add-SensorAttempt ('WMI ' + $ns)
                $sensors = Get-CimInstance -Namespace $ns -ClassName Sensor
                if (-not $sensors) { $sensors = Get-WmiObject -Namespace $ns -Class Sensor }
                foreach ($sensor in $sensors) {
                  if ([string]$sensor.SensorType -ne 'Temperature') { continue }
                  Set-TemperatureFromSensor $ns ([string]$sensor.HardwareType) ([string]$sensor.Parent) ([string]$sensor.Name) ([string]$sensor.Identifier) ([double]$sensor.Value)
                }
                if ($cpuTemp -ge 0 -and $gpuTemp -ge 0) { break }
              } catch { Add-SensorAttempt ('WMI failed ' + $ns); Add-SensorError(('WMI ' + $ns), $_.Exception.Message) }
            }

            if ($cpuTemp -lt 0) {
              try {
                Add-SensorAttempt('Core Temp Shared Memory')
                $mappingNames = @('CoreTempMappingObjectEx', 'CoreTempMappingObject')
                foreach ($mappingName in $mappingNames) {
                  try {
                    $mmf = [System.IO.MemoryMappedFiles.MemoryMappedFile]::OpenExisting($mappingName)
                    $view = $mmf.CreateViewStream(0, 0, [System.IO.MemoryMappedFiles.MemoryMappedFileAccess]::Read)
                    $reader = New-Object System.IO.BinaryReader($view)
                    $view.Position = 1536
                    $coreCount = $reader.ReadUInt32()
                    $cpuCount = $reader.ReadUInt32()
                    $temps = @()
                    for ($i = 0; $i -lt [Math]::Min([int]$coreCount, 256); $i++) {
                      $tempValue = $reader.ReadSingle()
                      if ($tempValue -gt 0) {
                        $temps += [double]$tempValue
                      }
                    }
                    $view.Position = 2684
                    $fahrenheit = $reader.ReadByte()
                    $deltaToTjMax = $reader.ReadByte()
                    $null = $reader.ReadByte()
                    $null = $reader.ReadByte()
                    $view.Position = 1024
                    $tjMax = $reader.ReadUInt32()
                    if ($temps.Count -gt 0) {
                      if ($deltaToTjMax -ne 0 -and $tjMax -gt 0) {
                        $temps = $temps | ForEach-Object { [double]$tjMax - $_ }
                      }
                      if ($fahrenheit -ne 0) {
                        $temps = $temps | ForEach-Object { ([double]$_ - 32.0) * 5.0 / 9.0 }
                      }
                      $cpuTemp = [Math]::Round(($temps | Measure-Object -Maximum).Maximum, 1)
                      $cpuSensorSource = 'Core Temp Shared Memory'
                      $cpuSensorMatch = $mappingName + ' / max-core'
                      $reader.Close()
                      $view.Close()
                      $mmf.Dispose()
                      break
                    }
                    $reader.Close()
                    $view.Close()
                    $mmf.Dispose()
                  } catch {}
                }
              } catch { Add-SensorAttempt('Core Temp Shared Memory failed'); Add-SensorError('Core Temp Shared Memory', $_.Exception.Message) }
            }

            if ($cpuTemp -lt 0 -or $gpuTemp -lt 0) {
              try {
                Add-SensorAttempt('HWiNFO Shared Memory')
                $mappingNames = @('Global\\HWiNFO_SENS_SM2', 'HWiNFO_SENS_SM2')
                foreach ($mappingName in $mappingNames) {
                  try {
                    $mmf = [System.IO.MemoryMappedFiles.MemoryMappedFile]::OpenExisting($mappingName)
                    $view = $mmf.CreateViewStream(0, 0, [System.IO.MemoryMappedFiles.MemoryMappedFileAccess]::Read)
                    $reader = New-Object System.IO.BinaryReader($view)
                    $signature = [System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(4))
                    if ($signature -ne 'HWiN') {
                      $reader.Close()
                      $view.Close()
                      $mmf.Dispose()
                      continue
                    }
                    $null = $reader.ReadUInt32()
                    $null = $reader.ReadUInt32()
                    $null = $reader.ReadUInt64()
                    $null = $reader.ReadUInt32()
                    $null = $reader.ReadUInt32()
                    $null = $reader.ReadUInt32()
                    $readingOffset = $reader.ReadUInt32()
                    $readingSize = $reader.ReadUInt32()
                    $readingCount = $reader.ReadUInt32()
                    for ($i = 0; $i -lt [Math]::Min([int]$readingCount, 4096); $i++) {
                      $view.Position = $readingOffset + ($i * $readingSize)
                      $null = $reader.ReadUInt32()
                      $null = $reader.ReadUInt32()
                      $null = $reader.ReadUInt32()
                      $labelOrig = ([System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(128))).Split([char]0)[0]
                      $labelUser = ([System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(128))).Split([char]0)[0]
                      $unit = ([System.Text.Encoding]::ASCII.GetString($reader.ReadBytes(16))).Split([char]0)[0]
                      $value = $reader.ReadDouble()
                      $null = $reader.ReadDouble()
                      $null = $reader.ReadDouble()
                      $null = $reader.ReadDouble()
                      if ([string]::IsNullOrWhiteSpace($unit) -or $unit -notmatch 'C') { continue }
                      $label = if ([string]::IsNullOrWhiteSpace($labelUser)) { $labelOrig } else { $labelUser }
                      if ([string]::IsNullOrWhiteSpace($label)) { continue }
                      Set-TemperatureFromSensor 'HWiNFO Shared Memory' '' $label $label $label ([double]$value)
                    }
                    $reader.Close()
                    $view.Close()
                    $mmf.Dispose()
                    if ($cpuTemp -ge 0 -and $gpuTemp -ge 0) { break }
                  } catch {}
                }
              } catch { Add-SensorAttempt('HWiNFO Shared Memory failed'); Add-SensorError('HWiNFO Shared Memory', $_.Exception.Message) }
            }

            if ($cpuTemp -lt 0) {
              try {
                Add-SensorAttempt('MSAcpi_ThermalZoneTemperature')
                $zones = Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature
                if (-not $zones) { $zones = Get-WmiObject -Namespace root/wmi -Class MSAcpi_ThermalZoneTemperature }
                $zone = $zones | Where-Object { $_.CurrentTemperature -gt 0 } | Select-Object -First 1
                if ($zone) {
                  $cpuTemp = [math]::Round(($zone.CurrentTemperature / 10.0) - 273.15, 1)
                  $cpuSensorSource = 'MSAcpi_ThermalZoneTemperature'
                  $cpuSensorMatch = [string]$zone.InstanceName
                }
              } catch { Add-SensorAttempt('MSAcpi_ThermalZoneTemperature failed'); Add-SensorError('MSAcpi_ThermalZoneTemperature', $_.Exception.Message) }
            }

            if ($cpuTemp -lt 0) {
              try {
                Add-SensorAttempt('Thermal Zone Counters')
                $thermalCounter = Get-Counter '\\Thermal Zone Information(*)\\Temperature'
                $thermalSample = $thermalCounter.CounterSamples | Where-Object { $_.CookedValue -gt 0 } | Sort-Object CookedValue -Descending | Select-Object -First 1
                if ($thermalSample) {
                  $cpuTemp = [double]$thermalSample.CookedValue
                  $cpuSensorSource = 'Thermal Zone Counter'
                  $cpuSensorMatch = [string]$thermalSample.Path
                }
              } catch { Add-SensorAttempt('Thermal Zone Counters failed'); Add-SensorError('Thermal Zone Counters', $_.Exception.Message) }
            }

            if ($gpuTemp -lt 0 -or ($gpuLoad -lt 0 -and $activeVendor.ToLowerInvariant().Contains('nvidia'))) {
              try {
                Add-SensorAttempt('nvidia-smi')
                $nvidiaSmi = Get-Command nvidia-smi.exe -ErrorAction SilentlyContinue
                if ($nvidiaSmi) {
                  $gpuRows = & $nvidiaSmi.Source '--query-gpu=index,name,temperature.gpu,utilization.gpu' '--format=csv,noheader,nounits' 2>$null
                  $selected = $null
                  foreach ($gpuRow in $gpuRows) {
                    $parts = $gpuRow -split ','
                    if ($parts.Count -lt 4) { continue }
                    $name = $parts[1].Trim()
                    $preferred = Test-PreferredGpu $name $name $name
                    if ($preferred) {
                      $selected = $parts
                      break
                    }
                    if ($null -eq $selected) {
                      $selected = $parts
                    }
                  }
                  if ($selected) {
                    $tempValue = [double]($selected[2].Trim())
                    $utilValue = [double]($selected[3].Trim())
                    if ($tempValue -gt 0) {
                      $gpuTemp = $tempValue
                      $gpuTempSensorSource = 'nvidia-smi'
                      $gpuTempSensorMatch = $selected[1].Trim()
                    }
                    if ($utilValue -ge 0) {
                      $gpuLoad = $utilValue
                      $counterSource = 'nvidia-smi + Windows Performance Counters'
                    }
                  }
                }
              } catch { Add-SensorAttempt('nvidia-smi failed'); Add-SensorError('nvidia-smi', $_.Exception.Message) }
            }

            $attemptText = if ($sensorAttempts.Count -gt 0) { ' | Tried: ' + (($sensorAttempts | Select-Object -Unique) -join ', ') } else { '' }
            $errorText = if ($sensorErrors.Count -gt 0) { (($sensorErrors | Select-Object -Unique) -join ' | ') } else { 'none' }
            $sensorSource = 'CPU: ' + $cpuSensorSource + ' [' + $cpuSensorMatch + '] | GPU: ' + $gpuTempSensorSource + ' [' + $gpuTempSensorMatch + '] | GPU Hot Spot: ' + $gpuHotSpotSensorSource + ' [' + $gpuHotSpotSensorMatch + ']' + $attemptText
            $result = @{
              bridgeActive = $true
              counterSource = $counterSource
              sensorSource = $sensorSource
              sensorErrorCode = $errorText
              cpuTemperatureProvider = $cpuSensorSource
              gpuTemperatureProvider = $gpuTempSensorSource
              gpuHotSpotTemperatureProvider = $gpuHotSpotSensorSource
              cpuCoreLoadPercent = $cpuLoad
              gpuCoreLoadPercent = $gpuLoad
              gpuTemperatureC = $gpuTemp
              gpuHotSpotTemperatureC = $gpuHotSpot
              cpuTemperatureC = $cpuTemp
              bytesReceivedPerSecond = [int64][math]::Round($netRecv)
              bytesSentPerSecond = [int64][math]::Round($netSent)
              diskReadBytesPerSecond = [int64][math]::Round($diskRead)
              diskWriteBytesPerSecond = [int64][math]::Round($diskWrite)
              sampleDurationMillis = [int64]([System.Environment]::TickCount64 - $sampleStartedAt)
            }
            $result | ConvertTo-Json -Compress
            """;

    private final AtomicBoolean helperStarting = new AtomicBoolean(false);
    private final AtomicBoolean fallbackRefreshInFlight = new AtomicBoolean(false);
    private final Object helperLock = new Object();
    private volatile Sample latest = Sample.empty();
    private volatile long lastRequestAtMillis;
    private volatile long helperStartedAtMillis;
    private volatile Process helperProcess;
    private Process fallbackProcess;
    private boolean closed;
    private volatile boolean collectionActive = true;
    private volatile Thread helperReaderThread;
    private volatile int helperIntervalMillis = -1;
    private volatile String helperRenderer = "";
    private volatile String helperVendor = "";

    WindowsTelemetryBridge() {
        if (isSupported()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "taskmanager-telemetry-shutdown"));
        }
    }

    void setCollectionActive(boolean active) {
        if (collectionActive == active) return;
        synchronized (helperLock) {
            collectionActive = active;
            if (active) return;
            stopHelperLocked();
            if (fallbackProcess != null) {
                fallbackProcess.destroyForcibly();
                fallbackProcess = null;
            }
        }
    }

    void close() {
        synchronized (helperLock) {
            closed = true;
            stopHelperLocked();
            if (fallbackProcess != null) {
                fallbackProcess.destroyForcibly();
                fallbackProcess = null;
            }
        }
    }

    boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    void requestRefreshIfNeeded(boolean fastNativeTelemetryAvailable) {
        if (!isSupported()) {
            latest = Sample.empty();
            stopHelper();
            return;
        }

        long now = System.currentTimeMillis();
        int requestIntervalMillis = ProfilerManager.getInstance().isProfilerSelfProtectionActive()
                ? 4_000
                : ProfilerManager.getInstance().shouldCollectFrameMetrics()
                ? (fastNativeTelemetryAvailable ? 2_000 : 1_000)
                : ConfigManager.getMetricsUpdateIntervalMs();
        SystemMetricsProfiler.Snapshot systemSnapshot = SystemMetricsProfiler.getInstance().getSnapshot();
        String activeRenderer = normalizeHelperValue(systemSnapshot.gpuRenderer());
        String activeVendor = normalizeHelperValue(systemSnapshot.gpuVendor());
        if (helperNeedsRestart(requestIntervalMillis, activeRenderer, activeVendor)) {
            restartHelper(requestIntervalMillis, activeRenderer, activeVendor);
            return;
        }
        if (helperReaderThread != null && helperReaderThread.isAlive() && now - lastRequestAtMillis < requestIntervalMillis) {
            requestFallbackRefreshIfStale(requestIntervalMillis, activeRenderer, activeVendor);
            return;
        }
        lastRequestAtMillis = now;
        if (!isHelperAlive()) {
            restartHelper(requestIntervalMillis, activeRenderer, activeVendor);
        }
        requestFallbackRefreshIfStale(requestIntervalMillis, activeRenderer, activeVendor);
    }

    Sample getLatest() {
        return latest;
    }

    Health getHealth() {
        long now = System.currentTimeMillis();
        long helperUptimeMillis = helperStartedAtMillis <= 0L ? 0L : Math.max(0L, now - helperStartedAtMillis);
        long latestSampleAgeMillis = latest.capturedAtEpochMillis() <= 0L ? Long.MAX_VALUE : Math.max(0L, now - latest.capturedAtEpochMillis());
        String helperStatus;
        if (!isSupported()) {
            helperStatus = "unsupported";
        } else if (fallbackRefreshInFlight.get()) {
            helperStatus = "fallback";
        } else if (isHelperAlive()) {
            helperStatus = latestSampleAgeMillis > Math.max(10_000L, helperIntervalMillis * 5L) ? "stale" : "alive";
        } else {
            helperStatus = "stopped";
        }
        return new Health(helperStatus, latestSampleAgeMillis, helperUptimeMillis, fallbackRefreshInFlight.get(), latest.sampleDurationMillis());
    }

    private boolean helperNeedsRestart(int intervalMillis, String renderer, String vendor) {
        if (!isHelperAlive()) {
            return true;
        }
        if (helperIntervalMillis != intervalMillis) {
            return true;
        }
        if (!helperRenderer.equals(renderer)) {
            return true;
        }
        if (!helperVendor.equals(vendor)) {
            return true;
        }
        long now = System.currentTimeMillis();
        long helperUptimeMillis = helperStartedAtMillis <= 0L ? Long.MAX_VALUE : Math.max(0L, now - helperStartedAtMillis);
        long startupGraceMillis = Math.max(4_000L, intervalMillis * 3L);
        if (latest.capturedAtEpochMillis() <= 0L) {
            return helperUptimeMillis > startupGraceMillis * 2L;
        }
        long age = Math.max(0L, now - latest.capturedAtEpochMillis());
        return helperUptimeMillis > startupGraceMillis && age > Math.max(10_000L, intervalMillis * 5L);
    }

    private boolean isHelperAlive() {
        Process process = helperProcess;
        return process != null && process.isAlive();
    }

    private void restartHelper(int intervalMillis, String renderer, String vendor) {
        if (!helperStarting.compareAndSet(false, true)) {
            return;
        }
        try {
            synchronized (helperLock) {
                if (closed || !collectionActive) return;
                stopHelperLocked();
                ProcessBuilder processBuilder = new ProcessBuilder(
                        "powershell",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-Command",
                        buildLoopScript(intervalMillis)
                ).redirectErrorStream(true);
                processBuilder.environment().put("TM_ACTIVE_RENDERER", renderer);
                processBuilder.environment().put("TM_ACTIVE_VENDOR", vendor);
                Process process = processBuilder.start();
                helperProcess = process;
                helperIntervalMillis = intervalMillis;
                helperRenderer = renderer;
                helperVendor = vendor;
                helperStartedAtMillis = System.currentTimeMillis();
                lastRequestAtMillis = helperStartedAtMillis;
                Thread readerThread = new Thread(() -> readHelperOutput(process), "taskmanager-windows-telemetry");
                readerThread.setDaemon(true);
                helperReaderThread = readerThread;
                readerThread.start();
            }
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("Windows telemetry bridge failed to start helper: {}", e.getMessage());
        } finally {
            helperStarting.set(false);
        }
    }

    private void readHelperOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String json = line.trim();
                if (json.isEmpty() || !json.startsWith("{")) {
                    continue;
                }
                Sample parsed = parseSample(json);
                if (parsed != null) {
                    latest = mergeWithPrevious(parsed, latest);
                }
            }
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("Windows telemetry bridge helper stopped: {}", e.getMessage());
        } finally {
            synchronized (helperLock) {
                if (helperProcess == process) {
                    stopHelperLocked();
                }
            }
        }
    }

    private void requestFallbackRefreshIfStale(int intervalMillis, String renderer, String vendor) {
        long now = System.currentTimeMillis();
        long helperUptimeMillis = helperStartedAtMillis <= 0L ? Long.MAX_VALUE : Math.max(0L, now - helperStartedAtMillis);
        long age = latest.capturedAtEpochMillis() > 0L
                ? Math.max(0L, now - latest.capturedAtEpochMillis())
                : helperUptimeMillis;
        long fallbackDelayMillis = latest.capturedAtEpochMillis() > 0L
                ? Math.max(3_000L, intervalMillis * 2L)
                : Math.max(1_500L, intervalMillis);
        if (age <= fallbackDelayMillis || !fallbackRefreshInFlight.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(() -> runFallbackRefresh(renderer, vendor), "taskmanager-windows-telemetry-fallback");
        thread.setDaemon(true);
        thread.start();
    }

    private void runFallbackRefresh(String renderer, String vendor) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    "powershell",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    buildProcessScript(SCRIPT, 0, ProcessHandle.current().pid())
            ).redirectErrorStream(true);
            processBuilder.environment().put("TM_ACTIVE_RENDERER", renderer);
            processBuilder.environment().put("TM_ACTIVE_VENDOR", vendor);
            synchronized (helperLock) {
                if (closed || !collectionActive) return;
                process = processBuilder.start();
                fallbackProcess = process;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String json = line.trim();
                    if (json.isEmpty() || !json.startsWith("{")) {
                        continue;
                    }
                    Sample parsed = parseSample(json);
                    if (parsed != null) {
                        latest = mergeWithPrevious(parsed, latest);
                        break;
                    }
                }
            }
            process.waitFor();
        } catch (Exception e) {
            taskmanagerClient.LOGGER.debug("Windows telemetry fallback refresh failed: {}", e.getMessage());
        } finally {
            synchronized (helperLock) {
                if (process != null) process.destroyForcibly();
                if (fallbackProcess == process) fallbackProcess = null;
            }
            fallbackRefreshInFlight.set(false);
        }
    }

    private Sample parseSample(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return new Sample(
                    System.currentTimeMillis(),
                    getBoolean(root, "bridgeActive"),
                    getString(root, "counterSource"),
                    getString(root, "sensorSource"),
                    getString(root, "sensorErrorCode"),
                    getString(root, "cpuTemperatureProvider"),
                    getString(root, "gpuTemperatureProvider"),
                    getString(root, "gpuHotSpotTemperatureProvider"),
                    getDouble(root, "cpuCoreLoadPercent"),
                    getDouble(root, "gpuCoreLoadPercent"),
                    getDouble(root, "gpuTemperatureC"),
                    getDouble(root, "gpuHotSpotTemperatureC"),
                    getDouble(root, "cpuTemperatureC"),
                    getLong(root, "bytesReceivedPerSecond"),
                    getLong(root, "bytesSentPerSecond"),
                    getLong(root, "diskReadBytesPerSecond"),
                    getLong(root, "diskWriteBytesPerSecond"),
                    getLong(root, "sampleDurationMillis")
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildLoopScript(int intervalMillis) {
        return buildProcessScript(SCRIPT, Math.max(250, intervalMillis), ProcessHandle.current().pid());
    }

    static String buildProcessScript(String sampleScript, int intervalMillis, long parentPid) {
        // Pin the parent process handle so a reused PID cannot keep an orphan alive.
        return "$ProgressPreference='SilentlyContinue'; try { $tmParent=[System.Diagnostics.Process]::GetProcessById("
                + parentPid + "); $null=$tmParent.Handle } catch { exit }; try { while (!$tmParent.HasExited) { & { "
                + sampleScript + " }; "
                + (intervalMillis <= 0 ? "break" : "if ($tmParent.WaitForExit(" + Math.max(250, intervalMillis) + ")) { break }")
                + " } } finally { $tmParent.Dispose() }";
    }

    private String normalizeHelperValue(String value) {
        return value == null ? "" : value;
    }

    private void stopHelper() {
        synchronized (helperLock) {
            stopHelperLocked();
        }
    }

    private void stopHelperLocked() {
        Process process = helperProcess;
        helperProcess = null;
        helperReaderThread = null;
        helperIntervalMillis = -1;
        helperRenderer = "";
        helperVendor = "";
        helperStartedAtMillis = 0L;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    private Sample mergeWithPrevious(Sample current, Sample previous) {
        if (previous == null) {
            previous = Sample.empty();
        }
        return new Sample(
                current.capturedAtEpochMillis() > 0L ? current.capturedAtEpochMillis() : previous.capturedAtEpochMillis(),
                current.bridgeActive(),
                chooseText(current.counterSource(), previous.counterSource(), "Unavailable"),
                chooseText(current.sensorSource(), previous.sensorSource(), "Unavailable"),
                chooseText(current.sensorErrorCode(), previous.sensorErrorCode(), "No bridge data"),
                chooseText(current.cpuTemperatureProvider(), previous.cpuTemperatureProvider(), "Unavailable"),
                chooseText(current.gpuTemperatureProvider(), previous.gpuTemperatureProvider(), "Unavailable"),
                chooseText(current.gpuHotSpotTemperatureProvider(), previous.gpuHotSpotTemperatureProvider(), "Unavailable"),
                chooseMetric(current.cpuCoreLoadPercent(), previous.cpuCoreLoadPercent()),
                chooseMetric(current.gpuCoreLoadPercent(), previous.gpuCoreLoadPercent()),
                chooseMetric(current.gpuTemperatureC(), previous.gpuTemperatureC()),
                chooseMetric(current.gpuHotSpotTemperatureC(), previous.gpuHotSpotTemperatureC()),
                chooseMetric(current.cpuTemperatureC(), previous.cpuTemperatureC()),
                chooseMetric(current.bytesReceivedPerSecond(), previous.bytesReceivedPerSecond()),
                chooseMetric(current.bytesSentPerSecond(), previous.bytesSentPerSecond()),
                chooseMetric(current.diskReadBytesPerSecond(), previous.diskReadBytesPerSecond()),
                chooseMetric(current.diskWriteBytesPerSecond(), previous.diskWriteBytesPerSecond()),
                current.sampleDurationMillis() > 0L ? current.sampleDurationMillis() : previous.sampleDurationMillis()
        );
    }

    private double chooseMetric(double current, double previous) {
        if (Double.isFinite(current) && current >= 0.0) {
            return current;
        }
        if (Double.isFinite(previous) && previous >= 0.0) {
            return previous;
        }
        return -1.0;
    }

    private long chooseMetric(long current, long previous) {
        if (current >= 0L) {
            return current;
        }
        if (previous >= 0L) {
            return previous;
        }
        return -1L;
    }

    private String chooseText(String current, String previous, String fallback) {
        if (current != null && !current.isBlank() && !current.equalsIgnoreCase("Unavailable") && !current.equalsIgnoreCase("No bridge data")) {
            return current;
        }
        if (previous != null && !previous.isBlank() && !previous.equalsIgnoreCase("Unavailable") && !previous.equalsIgnoreCase("No bridge data")) {
            return previous;
        }
        return fallback;
    }

    private boolean getBoolean(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return false;
        try {
            return root.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String getString(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return "Unavailable";
        try {
            String value = root.get(key).getAsString();
            return value == null || value.isBlank() ? "Unavailable" : value;
        } catch (Exception ignored) {
            return "Unavailable";
        }
    }

    private double getDouble(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return -1;
        try {
            double value = root.get(key).getAsDouble();
            return Double.isFinite(value) ? value : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private long getLong(JsonObject root, String key) {
        if (!root.has(key) || root.get(key).isJsonNull()) return -1;
        try {
            return root.get(key).getAsLong();
        } catch (Exception ignored) {
            return -1;
        }
    }
}




