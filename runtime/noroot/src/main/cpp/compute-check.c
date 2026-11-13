#include <vulkan/vulkan.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "compute-shader.h"

#define COUNT 1024
#define VK(call) do { VkResult r=(call); if(r!=VK_SUCCESS){fprintf(stderr,"%s: VkResult %d\n",#call,r);return 1;} } while(0)
static uint32_t cpu_values[COUNT];
static void *cpu_worker(void *arg) {
    size_t start=(size_t)arg;
    for(size_t i=start;i<COUNT;i+=4)cpu_values[i]=(uint32_t)i*3+7;
    return NULL;
}
static int cpu(void) {
    pthread_t workers[4];
    for(size_t i=0;i<4;i++)if(pthread_create(&workers[i],NULL,cpu_worker,(void*)i))return 1;
    for(int i=0;i<4;i++)pthread_join(workers[i],NULL);
    for(uint32_t i=0;i<COUNT;i++)if(cpu_values[i]!=i*3+7)return 1;
    puts("CPU_OK threads=4 values=1024");return 0;
}
static int gpu(int execute) {
    VkApplicationInfo app={.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO,.pApplicationName="Luma",.apiVersion=VK_API_VERSION_1_1};
    VkInstanceCreateInfo ici={.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,.pApplicationInfo=&app};
    VkInstance instance;VK(vkCreateInstance(&ici,NULL,&instance));
    uint32_t count=0;VK(vkEnumeratePhysicalDevices(instance,&count,NULL));
    if(!count){fprintf(stderr,"No Vulkan device\n");vkDestroyInstance(instance,NULL);return 1;}
    VkPhysicalDevice *devices=calloc(count,sizeof(*devices));VK(vkEnumeratePhysicalDevices(instance,&count,devices));
    VkPhysicalDevice physical=VK_NULL_HANDLE;uint32_t family=0;
    for(uint32_t i=0;i<count;i++) {
        VkPhysicalDeviceProperties p;vkGetPhysicalDeviceProperties(devices[i],&p);
        printf("VULKAN_DEVICE name=%s type=%u api=%u.%u.%u\n",p.deviceName,p.deviceType,VK_VERSION_MAJOR(p.apiVersion),VK_VERSION_MINOR(p.apiVersion),VK_VERSION_PATCH(p.apiVersion));
        if(p.deviceType==VK_PHYSICAL_DEVICE_TYPE_CPU)continue;
        uint32_t n=0;vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&n,NULL);
        VkQueueFamilyProperties *q=calloc(n,sizeof(*q));vkGetPhysicalDeviceQueueFamilyProperties(devices[i],&n,q);
        for(uint32_t j=0;j<n;j++)if(q[j].queueCount && (q[j].queueFlags&VK_QUEUE_COMPUTE_BIT)){physical=devices[i];family=j;break;}
        free(q);if(physical)break;
    }
    free(devices);
    if(!physical){fprintf(stderr,"No hardware compute queue\n");vkDestroyInstance(instance,NULL);return 1;}
    if(!execute){vkDestroyInstance(instance,NULL);return 0;}
    float priority=1;
    VkDeviceQueueCreateInfo qci={.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,.queueFamilyIndex=family,.queueCount=1,.pQueuePriorities=&priority};
    VkDeviceCreateInfo dci={.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,.queueCreateInfoCount=1,.pQueueCreateInfos=&qci};
    VkDevice device;VK(vkCreateDevice(physical,&dci,NULL,&device));
    VkQueue queue;vkGetDeviceQueue(device,family,0,&queue);
    VkBufferCreateInfo bci={.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,.size=COUNT*sizeof(uint32_t),.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,.sharingMode=VK_SHARING_MODE_EXCLUSIVE};
    VkBuffer buffer;VK(vkCreateBuffer(device,&bci,NULL,&buffer));
    VkMemoryRequirements required;vkGetBufferMemoryRequirements(device,buffer,&required);
    VkPhysicalDeviceMemoryProperties memory;vkGetPhysicalDeviceMemoryProperties(physical,&memory);
    uint32_t memory_type=UINT32_MAX;
    for(uint32_t i=0;i<memory.memoryTypeCount;i++)if((required.memoryTypeBits&(1u<<i)) && (memory.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))==(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)){memory_type=i;break;}
    if(memory_type==UINT32_MAX){fprintf(stderr,"No coherent host-visible buffer memory\n");return 1;}
    VkMemoryAllocateInfo mai={.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,.allocationSize=required.size,.memoryTypeIndex=memory_type};
    VkDeviceMemory allocation;VK(vkAllocateMemory(device,&mai,NULL,&allocation));VK(vkBindBufferMemory(device,buffer,allocation,0));
    uint32_t *values;VK(vkMapMemory(device,allocation,0,VK_WHOLE_SIZE,0,(void**)&values));
    for(uint32_t i=0;i<COUNT;i++)values[i]=i;
    VkDescriptorSetLayoutBinding binding={.binding=0,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.descriptorCount=1,.stageFlags=VK_SHADER_STAGE_COMPUTE_BIT};
    VkDescriptorSetLayoutCreateInfo slci={.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,.bindingCount=1,.pBindings=&binding};
    VkDescriptorSetLayout set_layout;VK(vkCreateDescriptorSetLayout(device,&slci,NULL,&set_layout));
    VkPipelineLayoutCreateInfo plci={.sType=VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,.setLayoutCount=1,.pSetLayouts=&set_layout};
    VkPipelineLayout pipeline_layout;VK(vkCreatePipelineLayout(device,&plci,NULL,&pipeline_layout));
    VkShaderModuleCreateInfo smci={.sType=VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,.codeSize=sizeof(luma_shader),.pCode=luma_shader};
    VkShaderModule shader;VK(vkCreateShaderModule(device,&smci,NULL,&shader));
    VkComputePipelineCreateInfo cpci={.sType=VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,.stage={.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,.stage=VK_SHADER_STAGE_COMPUTE_BIT,.module=shader,.pName="main"},.layout=pipeline_layout};
    VkPipeline pipeline;VK(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&cpci,NULL,&pipeline));
    VkDescriptorPoolSize pool_size={.type=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.descriptorCount=1};
    VkDescriptorPoolCreateInfo dpci={.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,.maxSets=1,.poolSizeCount=1,.pPoolSizes=&pool_size};
    VkDescriptorPool pool;VK(vkCreateDescriptorPool(device,&dpci,NULL,&pool));
    VkDescriptorSetAllocateInfo dsai={.sType=VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,.descriptorPool=pool,.descriptorSetCount=1,.pSetLayouts=&set_layout};
    VkDescriptorSet set;VK(vkAllocateDescriptorSets(device,&dsai,&set));
    VkDescriptorBufferInfo dbi={.buffer=buffer,.offset=0,.range=COUNT*sizeof(uint32_t)};
    VkWriteDescriptorSet write={.sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,.dstSet=set,.dstBinding=0,.descriptorCount=1,.descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,.pBufferInfo=&dbi};
    vkUpdateDescriptorSets(device,1,&write,0,NULL);
    VkCommandPoolCreateInfo pci={.sType=VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,.queueFamilyIndex=family};
    VkCommandPool command_pool;VK(vkCreateCommandPool(device,&pci,NULL,&command_pool));
    VkCommandBufferAllocateInfo cbai={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,.commandPool=command_pool,.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY,.commandBufferCount=1};
    VkCommandBuffer command;VK(vkAllocateCommandBuffers(device,&cbai,&command));
    VkCommandBufferBeginInfo begin={.sType=VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};VK(vkBeginCommandBuffer(command,&begin));
    VkMemoryBarrier before={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT,.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT};
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&before,0,NULL,0,NULL);
    vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline_layout,0,1,&set,0,NULL);
    vkCmdDispatch(command,COUNT/64,1,1);
    VkMemoryBarrier after={.sType=VK_STRUCTURE_TYPE_MEMORY_BARRIER,.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT,.dstAccessMask=VK_ACCESS_HOST_READ_BIT};
    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&after,0,NULL,0,NULL);
    VK(vkEndCommandBuffer(command));
    VkFenceCreateInfo fci={.sType=VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VkFence fence;VK(vkCreateFence(device,&fci,NULL,&fence));
    VkSubmitInfo submit={.sType=VK_STRUCTURE_TYPE_SUBMIT_INFO,.commandBufferCount=1,.pCommandBuffers=&command};VK(vkQueueSubmit(queue,1,&submit,fence));
    VK(vkWaitForFences(device,1,&fence,VK_TRUE,UINT64_C(10000000000)));
    int ok=1;for(uint32_t i=0;i<COUNT;i++)if(values[i]!=i*3+7){ok=0;break;}
    vkUnmapMemory(device,allocation);vkDestroyFence(device,fence,NULL);vkDestroyCommandPool(device,command_pool,NULL);
    vkDestroyDescriptorPool(device,pool,NULL);vkDestroyPipeline(device,pipeline,NULL);vkDestroyShaderModule(device,shader,NULL);
    vkDestroyPipelineLayout(device,pipeline_layout,NULL);vkDestroyDescriptorSetLayout(device,set_layout,NULL);
    vkDestroyBuffer(device,buffer,NULL);vkFreeMemory(device,allocation,NULL);vkDestroyDevice(device,NULL);vkDestroyInstance(instance,NULL);
    if(!ok){fprintf(stderr,"GPU result mismatch\n");return 1;}
    puts("GPU_COMPUTE_OK values=1024");return 0;
}
int main(int argc,char **argv) {
    printf("UID=%u\n",(unsigned)getuid());
    FILE *f=fopen("/proc/self/attr/current","r");if(f){char context[256];if(fgets(context,sizeof(context),f))printf("SELINUX=%s\n",context);fclose(f);}
    if(argc>1 && !strcmp(argv[1],"--cpu"))return cpu();
    if(argc>1 && !strcmp(argv[1],"--list"))return gpu(0);
    if(argc>1 && !strcmp(argv[1],"--gpu"))return gpu(1);
    return cpu() || gpu(1);
}
