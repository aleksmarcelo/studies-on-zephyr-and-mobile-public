#!/usr/bin/env python3

import sys
import argparse
import subprocess
import json

class NordicChipInfo:
    FICR_BASE = 0x10000000
    
    # Offsets relative to FICR_BASE
    REGISTERS = {
        'CODEPAGESIZE':     0x010,  # Code page size
        'CODESIZE':         0x014,  # Number of code pages
        'CLENR0':          0x028,  # Code region 0 length
        'PPFC':            0x02C,  # Pre-programmed factory code present
        'NUMRAMBLOCK':     0x034,  # Number of individually controllable RAM blocks
        'SIZERAMBLOCK':    0x038,  # Size of RAM blocks in bytes
        'SIZERAMBLOCKS':   0x03C,  # Size of RAM blocks in bytes
        'CONFIGID':        0x05C,  # Configuration identifier
        'DEVICEID':        0x060,  # Device identifier (2 words)
        'ER':              0x080,  # Encryption root (4 words)
        'IR':              0x090,  # Identity root (4 words)
        'DEVICEADDRTYPE':  0x0A0,  # Device address type
        'DEVICEADDR':      0x0A4,  # Device address (2 words)
        'OVERRIDEEN':      0x0AC,  # Override enable
        'NRF_1MBIT':       0x0B0,  # Override values for 1 Mbit mode (5 words)
        'BLE_1MBIT':       0x0EC,  # Override values for BLE in 1 Mbit mode (5 words)
        'PART':            0x100,  # Part code
        'VARIANT':         0x104,  # Part variant
        'PACKAGE':         0x108,  # Package option
        'RAM':             0x10C,  # RAM variant
        'FLASH':           0x110,  # Flash variant
        'TAGHEADER0':      0x120,  # Default header for NFC Tag
        'TAGHEADER1':      0x124,  # Default header for NFC Tag
        'TAGHEADER2':      0x128,  # Default header for NFC Tag
        'TAGHEADER3':      0x12C,  # Default header for NFC Tag
    }
    
    # Known part codes
    PART_CODES = {
        0x52832: "nRF52832",
        0x52840: "nRF52840",
        0x52833: "nRF52833",
        0x52811: "nRF52811",
        0x52820: "nRF52820",
        0x5340:  "nRF5340 (Application Core)",
        0x5341:  "nRF5340 (Network Core)",
        0x9160:  "nRF9160",
        0x9161:  "nRF9161",
        0x197b:  "nRF52832 (Alternative ID)",
    }
    
    # Known variants
    VARIANT_CODES = {
        0x41414141: "AAAA - Standard variant, 64MHz, -40°C to +85°C",
        0x41414142: "AAAB - High temperature variant, 64MHz, -40°C to +105°C",
        0x41414241: "AABA - Extended temperature variant, 64MHz, -40°C to +125°C",
        0x41414341: "AACA - Industrial temperature variant, 64MHz, -40°C to +105°C",
        0xd39e:     "D39E - Custom variant, 64MHz, -40°C to +85°C",
    }
    
    # Variant characteristics
    VARIANT_CHARACTERISTICS = {
        "AAAA": {
            "speed": "64 MHz",
            "temperature": "-40°C to +85°C",
            "package": "Standard",
            "description": "Standard commercial variant"
        },
        "AAAB": {
            "speed": "64 MHz",
            "temperature": "-40°C to +105°C",
            "package": "Extended",
            "description": "High temperature variant for industrial applications"
        },
        "AABA": {
            "speed": "64 MHz",
            "temperature": "-40°C to +125°C",
            "package": "Extended",
            "description": "Extended temperature variant for harsh environments"
        },
        "AACA": {
            "speed": "64 MHz",
            "temperature": "-40°C to +105°C",
            "package": "Industrial",
            "description": "Industrial temperature variant with enhanced reliability"
        },
        "D39E": {
            "speed": "64 MHz",
            "temperature": "-40°C to +85°C",
            "package": "Standard",
            "description": "Custom variant with standard specifications"
        }
    }
    
    # Package types
    PACKAGE_CODES = {
        0x2000: "QI (6x6mm)",
        0x2001: "QC (7x7mm)", 
        0x2002: "CK (WLCSP)",
        0x2005: "QD (5x5mm)",
        0x2006: "QN (8x8mm)",
        0x2007: "QH (7x7mm)",
        0x2008: "QJ (8x8mm)",
        0x2009: "QK (9x9mm)",
        0x200A: "QL (10x10mm)",
        0x200B: "QM (11x11mm)",
        0x200C: "QN (12x12mm)",
        0x200D: "QO (13x13mm)",
        0x200E: "QP (14x14mm)",
        0x200F: "QQ (15x15mm)",
        0x2010: "QR (16x16mm)",
        0x2011: "QS (17x17mm)",
        0x2012: "QT (18x18mm)",
        0x2013: "QU (19x19mm)",
        0x2014: "QV (20x20mm)",
        0x2015: "QW (21x21mm)",
        0x2016: "QX (22x22mm)",
        0x2017: "QY (23x23mm)",
        0x2018: "QZ (24x24mm)",
    }

    # PPFC (Pre-programmed factory code) values
    PPFC_VALUES = {
        0xFFFFFFFF: "No pre-programmed factory code present",
        0x00000001: "Pre-programmed factory code present"
    }

    # Device address type values
    DEVICEADDRTYPE_VALUES = {
        0xFFFFFFFF: "No device address available",
        0x00000000: "Public address",
        0x00000001: "Random address"
    }

    # Override enable values
    OVERRIDEEN_VALUES = {
        0xFFFFFFFF: "Override disabled",
        0x00000001: "Override enabled"
    }

    def __init__(self, debug=False):
        self.chip_info = {}
        self.debug_mode = debug
        self.target_name = None
        
        # ANSI color codes
        self.BLUE = '\033[94m'
        self.WHITE = '\033[37m'
        self.YELLOW = '\033[93m'
        self.RESET = '\033[0m'
        
    def execute_pyocd_cmd(self, cmd):
        try:
            #todo: (?) Se ainda não temos o target, usa nrf52 como padrão inicial
            target = self.target_name if self.target_name else "nrf52"
            full_cmd = f"pyocd cmd -t {target} -c '{cmd}'"
            self.debug(f"Executing: {full_cmd}")
            result = subprocess.run(full_cmd, shell=True, capture_output=True, text=True)
            if result.returncode != 0:
                raise RuntimeError(f"Error executing command: {result.stderr}")
            return result.stdout.strip()
        except Exception as e:
            self.error(f"Error executing pyocd command: {str(e)}")
            raise

    def determine_target(self):
        try:
            part = self.chip_info.get('PART', 0)
            variant = self.chip_info.get('VARIANT', 0)
            
            self.debug(f"Determining target from PART: {part:#x}, VARIANT: {variant:#x}")
            
            # Mapeamento de PART para target
            part_to_target = {
                0x52832: "nrf52832",
                0x52840: "nrf52840",
                0x52833: "nrf52833",
                0x52811: "nrf52811",
                0x52820: "nrf52820",
                0x5340: "nrf5340",
                0x5341: "nrf5340",
                0x9160: "nrf9160",
                0x9161: "nrf9161",
                0x197b: "nrf52832"  # ID alternativo do nRF52832
            }
            
            # Determina o target baseado no PART
            self.target_name = part_to_target.get(part, "nrf52")
            self.debug(f"Target determined: {self.target_name}")
            
            return self.target_name
            
        except Exception as e:
            self.error(f"Error determining target: {str(e)}")
            return "nrf52"  # Fallback para nrf52 em caso de erro

    def read_register(self, offset, count=1):
        addr = self.FICR_BASE + offset
        cmd = f"halt; read32 {addr:#x} 4"  # Always read 4 bytes (32 bits)
        result = self.execute_pyocd_cmd(cmd)
        self.debug(f"Raw result for {addr:#x}: {result}")
        # Extract hexadecimal values from result
        for line in result.split('\n'):
            if ':' in line:
                try:
                    # Format is: "address: value |ascii|"
                    hex_value = line.split(':')[1].split('|')[0].strip()
                    value = int(hex_value, 16)
                    self.debug(f"Converted value for {addr:#x}: {value:#x}")
                    return value
                except (ValueError, IndexError) as e:
                    self.debug(f"Error converting value: {line} - {str(e)}")
                    continue
        raise ValueError(f"Could not read register at {addr:#x}")

    def connect(self):
        try:
            self.debug("Starting connection...")
            
            # First, try to halt the target
            self.execute_pyocd_cmd("halt")
            
            # Read all registers
            print("Reading internal chip memory", end='', flush=True)
            self.debug("Reading information registers...")
            for name, offset in self.REGISTERS.items():
                try:
                    value = self.read_register(offset)
                    self.chip_info[name] = value
                    self.debug(f"{name}: {value:#010x}")
                    print(".", end='', flush=True)  # Progress indicator
                except Exception as e:
                    self.debug(f"Error reading {name}: {str(e)}")
                    print("x", end='', flush=True)  # Error indicator
            print(" Done!")  # New line after progress
            
            # Determina o target baseado na memória lida
            self.determine_target()
            
            # Display chip information
            self.display_info()
                
        except Exception as e:
            self.debug(f"General error: {str(e)}")
            raise
        
    def format_field(self, field_name, value):
        return f"{self.BLUE}{field_name}{self.RESET}: {self.WHITE}{value}{self.RESET}"

    def format_title(self, title):
        return f"{self.YELLOW}{title}{self.RESET}"

    def format_memory_value(self, value, unit="bytes", explanation=""):
        if value == 0xFFFFFFFF:
            return f"Not configured"
        return f"{value} {unit}"

    def display_info(self):
        try:
            print(f"\n{self.format_title('Chip Information:')}")
            print("-" * 50)
            
            # Chip identification
            device_id = (self.chip_info['DEVICEID'] & 0xFFFF0000) >> 16
            variant_id = self.chip_info['DEVICEID'] & 0x0000FFFF
            
            self.debug(f"Raw DEVICEID: {self.chip_info['DEVICEID']:#x}")
            self.debug(f"Extracted Device ID: {device_id:#x}")
            self.debug(f"Extracted Variant ID: {variant_id:#x}")
            
            print(self.format_field("Device ID", f"{device_id:#06x} ({device_id})"))
            print(self.format_field("Variant ID", f"{variant_id:#06x} ({variant_id})"))
            print(self.format_field("Configuration ID", f"{self.chip_info['CONFIGID']:#x}"))
            
            # Chip name from DEVICEID
            part_name = self.PART_CODES.get(device_id, "Unknown")
            print(self.format_field("Chip Name (from DEVICEID)", part_name))
            
            # Chip name from PART register
            part_value = self.chip_info.get('PART', 0)
            self.debug(f"PART register value: {part_value:#x}")
            part_name_from_part = self.PART_CODES.get(part_value, "Unknown")
            print(self.format_field("Chip Name (from PART register)", part_name_from_part))
            
            # Variants
            variant_name = self.VARIANT_CODES.get(variant_id, "Unknown")
            print(self.format_field("Chip Variant", variant_name))
            
            # Display variant characteristics if available
            if variant_name != "Unknown":
                variant_code = variant_name.split(" - ")[0]
                if variant_code in self.VARIANT_CHARACTERISTICS:
                    print(f"\n{self.format_title('Variant Characteristics:')}")
                    print(self.format_field("  Speed", self.VARIANT_CHARACTERISTICS[variant_code]['speed']))
                    print(self.format_field("  Temperature Range", self.VARIANT_CHARACTERISTICS[variant_code]['temperature']))
                    print(self.format_field("  Package Type", self.VARIANT_CHARACTERISTICS[variant_code]['package']))
                    print(self.format_field("  Description", self.VARIANT_CHARACTERISTICS[variant_code]['description']))
            
            # Memory information
            print(f"\n{self.format_title('Memory Information:')}")
            print(self.format_field("Code Page Size", self.format_memory_value(self.chip_info['CODEPAGESIZE'])))
            print(self.format_field("Number of Code Pages", self.format_memory_value(self.chip_info['CODESIZE'], "")))
            print(self.format_field("Code Region 0 Length", self.format_memory_value(self.chip_info['CLENR0'])))
            print(self.format_field("Number of RAM Blocks", self.format_memory_value(self.chip_info['NUMRAMBLOCK'], "")))
            print(self.format_field("RAM Block Size", self.format_memory_value(self.chip_info['SIZERAMBLOCK'])))
            flash_size = self.chip_info['FLASH']
            ram_size = self.chip_info['RAM']
            print(self.format_field("Flash Size", f"{flash_size} KB"))
            print(self.format_field("RAM Size", f"{ram_size} KB"))
            
            # Additional information
            print(f"\n{self.format_title('Additional Information:')}")
            print(self.format_field("Part Code", f"{self.chip_info['PART']}"))
            
            # Package information
            package_code = self.chip_info['PACKAGE']
            package_name = self.PACKAGE_CODES.get(package_code, "Unknown")
            print(self.format_field("Package Type", f"{package_name} (Code: {package_code:#x})"))
            
            # Factory code information
            ppfc_value = self.chip_info['PPFC']
            ppfc_desc = self.PPFC_VALUES.get(ppfc_value, "Unknown")
            print(self.format_field("Factory Code", f"{ppfc_desc} (Code: {ppfc_value:#x})"))
            
            # Device address information
            addr_type = self.chip_info['DEVICEADDRTYPE']
            addr_type_desc = self.DEVICEADDRTYPE_VALUES.get(addr_type, "Unknown")
            print(self.format_field("Device Address Type", f"{addr_type_desc} (Code: {addr_type:#x})"))
            if addr_type != 0xFFFFFFFF:
                print(self.format_field("Device Address", f"{self.chip_info['DEVICEADDR']:#x}"))
            
            # Override information
            override_en = self.chip_info['OVERRIDEEN']
            override_desc = self.OVERRIDEEN_VALUES.get(override_en, "Unknown")
            print(self.format_field("Override Status", f"{override_desc} (Code: {override_en:#x})"))
            
            print(self.format_field("Encryption Root", f"{self.chip_info['ER']:#010x}"))
            print(self.format_field("Identity Root", f"{self.chip_info['IR']:#010x}"))
            
            # NFC Tag information
            print(f"\n{self.format_title('NFC Tag Information:')}")
            for i in range(4):
                tag_header = self.chip_info.get(f'TAGHEADER{i}', None)
                if tag_header is not None:
                    print(self.format_field(f"NFC Tag Header {i}", f"{tag_header:#010x}"))
            
            print("-" * 50)
            
        except Exception as e:
            self.error(f"Error displaying chip information: {str(e)}")
            raise

    def debug(self, msg):
        if self.debug_mode:
            print(f"DEBUG: {msg}", file=sys.stderr)
        
    def error(self, msg):
        print(f"ERROR: {msg}", file=sys.stderr)
        
def main():
    parser = argparse.ArgumentParser(description="Nordic Chip Information Reader")
    parser.add_argument("-d", "--debug", action="store_true",
                        help="Enable debug output")
    args = parser.parse_args()
    
    chip_info = NordicChipInfo(debug=args.debug)
    try:
        chip_info.connect()
    except Exception as e:
        chip_info.error(f"Connection failed: {str(e)}")
        sys.exit(1)

if __name__ == "__main__":
    main()

"""
References:
- nRF52832 FICR: https://docs.nordicsemi.com/bundle/ps_nrf52832/page/ficr.html
- nRF52840 FICR: https://docs.nordicsemi.com/bundle/ps_nrf52840/page/ficr.html
- nRF52833 FICR: https://docs.nordicsemi.com/bundle/ps_nrf52833/page/ficr.html
- nRF52811 FICR: https://docs.nordicsemi.com/bundle/ps_nrf52811/page/ficr.html
- nRF52820 FICR: https://docs.nordicsemi.com/bundle/ps_nrf52820/page/ficr.html
- nRF5340 FICR: https://docs.nordicsemi.com/bundle/ps_nrf5340/page/ficr.html
- nRF9160 FICR: https://docs.nordicsemi.com/bundle/ps_nrf9160/page/ficr.html
- nRF9161 FICR: https://docs.nordicsemi.com/bundle/ps_nrf9161/page/ficr.html
"""

