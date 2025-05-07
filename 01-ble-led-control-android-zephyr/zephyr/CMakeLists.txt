cmake_minimum_required(VERSION 3.20.5)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(study01)

#Força que o runner padrão seja o esp32
#set(BOARD_FLASH_RUNNER esp32)

FILE(GLOB app_sources src/*.c)
target_sources(app PRIVATE ${app_sources})